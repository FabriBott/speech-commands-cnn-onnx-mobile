package com.example.voicesmarthome;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.jlibrosa.audio.JLibrosa;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtException;
import be.tarsos.dsp.AudioDispatcher;
import be.tarsos.dsp.AudioEvent;
import be.tarsos.dsp.AudioProcessor;
import be.tarsos.dsp.io.android.AudioDispatcherFactory;
import be.tarsos.dsp.util.fft.FFT;
import be.tarsos.dsp.util.fft.HannWindow;

/**
 * MelSpectrogramRecorder
 *
 * Records audio from the device microphone, computes a mel spectrogram in real
 * time using TarsosDSP, accumulates frames into a fixed-length circular buffer,
 * and – once enough frames have been collected – packages them as a flat
 * float[] tensor ready for ONNX Runtime inference.
 *
 * ── Gradle dependencies ───────────────────────────────────────────────────────
 *   repositories { maven { url 'https://jitpack.io' } }
 *   dependencies {
 *       implementation 'com.github.JorenSix:TarsosDSP:2.4'
 *       implementation 'com.microsoft.onnxruntime:onnxruntime-android:1.17.3'
 *   }
 *
 * ── Manifest permission ───────────────────────────────────────────────────────
 *   <uses-permission android:name="android.permission.RECORD_AUDIO" />
 *
 * ── Typical usage ─────────────────────────────────────────────────────────────
 *   MelSpectrogramRecorder recorder = new MelSpectrogramRecorder(
 *       context,
 *       frame  -> updateSpectrogram(frame),          // live per-frame UI
 *       tensor -> onnxRunner.runInference(tensor));  // full buffer ready
 *
 *   recorder.start();
 *   // ...
 *   recorder.stop();
 *   List<MelFrame> snapshot = recorder.getFrameSnapshot();
 * ─────────────────────────────────────────────────────────────────────────────
 */
public class MelSpectrogramRecorder {

    private static final String TAG = "MelSpectrogramRecorder";

    // ── Audio / FFT parameters ────────────────────────────────────────────────

    /** Capture sample rate (Hz). */
    public static final int SAMPLE_RATE = 16_000;
    /** FFT window length – must be a power of two. */
    public static final int FFT_SIZE = 2_048;
    /**
     * Hop size (samples). 50 % overlap keeps temporal resolution high while
     * halving redundant computation.
     */
    public static final int HOP_SIZE = 512;//FFT_SIZE / 2;

    // ── Mel filter-bank parameters ────────────────────────────────────────────
    public static final int NUM_MEL_BANDS = 128;
    public static final float MEL_FREQ_MIN = 0f;
    public static final float MEL_FREQ_MAX = SAMPLE_RATE / 2f;

    // ── Frame-buffer parameters ───────────────────────────────────────────────

    /**
     * Maximum number of mel frames kept in the circular buffer.
     *
     * At 22 050 Hz with HOP_SIZE = 512:
     *   frames per second ~= 22 050 / 512 ~= 43
     *   128 frames ~= ~3 seconds of audio
     *
     * Adjust to match your model's expected time-axis length.
     */
    public static final int MAX_FRAMES = 32;

    // ── Internal state ────────────────────────────────────────────────────────

    private final Context             context;
    private final MelFrameListener    frameListener;
    private final TensorReadyListener tensorListener;

    private ONNXModelRunner modelRunner;
    private JLibrosa jLibrosa = new JLibrosa();
    private AudioDispatcher dispatcher;
    private Thread          dispatcherThread;

    private final FFT     fft;
    private final float[] hannCoefficients;

    private double startTimestamp = 0;
    private double endTimestamp = 0;
    /**
     * Circular frame buffer – access always synchronised on frameLock.
     * Using ArrayList + manual head-pointer eviction avoids per-frame allocation
     * while keeping the code readable.
     */
    private final List<MelFrame> frameBuffer = new ArrayList<>(MAX_FRAMES);
    ArrayList<float[]> recordingBuffer = new ArrayList<>(MAX_FRAMES);
    private final Object         frameLock   = new Object();

    // ── Public interfaces ─────────────────────────────────────────────────────

    /**
     * Delivers one mel frame per audio hop, on the audio thread.
     * Post to the main thread before touching the UI.
     */
    public interface MelFrameListener {
        void onMelFrame(MelFrame frame);
    }

    /**
     * Fired on the audio thread each time the internal buffer is full
     * (MAX_FRAMES reached) and after every subsequent frame.
     *
     * The supplied OnnxTensorInput is pre-built and safe to pass directly to
     * OnnxInferenceRunner.runInference().
     */
    public interface TensorReadyListener {
        void onTensorReady(OnnxTensorInput tensor);
    }

    // ── MelFrame ──────────────────────────────────────────────────────────────

    /** A single time-slice of the mel spectrogram. */
    public static final class MelFrame {

        /**
         * Log-power mel-band energies (dB), length = NUM_MEL_BANDS.
         * Index 0 = lowest frequency band.
         */
        public final float[] melBands;

        /** Centre time of this frame in seconds since recording started. */
        public final double timeStampSeconds;

        /** Sequential frame index (0-based) since recording started. */
        public final long frameIndex;

        MelFrame(float[] melBands, double timeStampSeconds, long frameIndex) {
            this.melBands         = melBands;
            this.timeStampSeconds = timeStampSeconds;
            this.frameIndex       = frameIndex;
        }
    }

    // ── OnnxTensorInput ───────────────────────────────────────────────────────

    /**
     * Immutable snapshot of the frame buffer packaged as an ONNX-ready tensor.
     *
     * Shape: [1, 1, NUM_MEL_BANDS, numFrames]
     * (batch=1, channel=1, frequency, time) – the convention used by most
     * spectrogram-based audio classifiers. Adjust shape[] if your model uses a
     * different layout (e.g. [1, numFrames, NUM_MEL_BANDS]).
     *
     * Pass data and shape to OrtSession.run() via an OnnxTensor:
     *
     *   OnnxTensor t = OnnxTensor.createTensor(env,
     *       FloatBuffer.wrap(input.data), input.shape);
     *   Map<String, OnnxTensor> feed = Collections.singletonMap("input", t);
     *   OrtSession.Result result = session.run(feed);
     */
    public static final class OnnxTensorInput {

        /**
         * Flat float array in row-major order.
         * Layout: data[t * NUM_MEL_BANDS + m] = frame[t].melBands[m]
         */
        public final float[] data;

        /** ONNX tensor shape: [1, 1, NUM_MEL_BANDS, numFrames]. */
        public final long[] shape;

        /** Number of time frames captured (<= MAX_FRAMES). */
        public final int numFrames;

        /** Timestamp of the oldest frame in this snapshot (seconds). */
        public final double startTimeSeconds;

        /** Timestamp of the newest frame in this snapshot (seconds). */
        public final double endTimeSeconds;

        OnnxTensorInput(float[] data, long[] shape, int numFrames,
                        double startTimeSeconds, double endTimeSeconds) {
            this.data             = data;
            this.shape            = shape;
            this.numFrames        = numFrames;
            this.startTimeSeconds = startTimeSeconds;
            this.endTimeSeconds   = endTimeSeconds;
        }

        @Override
        public String toString() {
            return "OnnxTensorInput{shape=[1,1," + NUM_MEL_BANDS + "," + numFrames
                    + "], t=" + String.format("%.2f", startTimeSeconds)
                    + "->" + String.format("%.2f", endTimeSeconds) + "s}";
        }
    }

    // ── Constructors ──────────────────────────────────────────────────────────

    /**
     * Construct a recorder with both per-frame and tensor-ready callbacks.
     *
     * @param context        Android context for permission checking.
     * @param frameListener  nullable; called for every mel frame (UI updates).
     * @param tensorListener nullable; called whenever the buffer is full and a
     *                       fresh tensor is available for inference.
     */
    public MelSpectrogramRecorder(Context context,
                                  MelFrameListener frameListener,
                                  TensorReadyListener tensorListener) {
        this.context        = context.getApplicationContext();
        this.frameListener  = frameListener;
        this.tensorListener = tensorListener;
        this.modelRunner    = new ONNXModelRunner(context);

        fft              = new FFT(FFT_SIZE, new HannWindow());
        hannCoefficients = buildHannWindow(FFT_SIZE);
    }

    /** Convenience constructor – tensor callback only, no per-frame callback. */
    public MelSpectrogramRecorder(Context context, TensorReadyListener tensorListener) {
        this(context, null, tensorListener);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Start microphone capture and mel-spectrogram computation.
     *
     * @throws SecurityException     if RECORD_AUDIO has not been granted.
     * @throws IllegalStateException if already running.
     */
    public synchronized void start() {
        if (dispatcher != null) {
            throw new IllegalStateException("Already running – call stop() first.");
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException(
                    "RECORD_AUDIO permission is required before calling start().");
        }

        synchronized (frameLock) { frameBuffer.clear(); }

        //clear custom recording buffer
        synchronized (frameLock) {
            for (int i = 0; i < recordingBuffer.size(); i++) {
                float[] newFrame = new float[FFT_SIZE];
                Arrays.fill(newFrame, 0);
                recordingBuffer.add(newFrame);
            }
        }

        dispatcher = AudioDispatcherFactory.fromDefaultMicrophone(SAMPLE_RATE, FFT_SIZE, FFT_SIZE - HOP_SIZE);

        //dispatcher.addAudioProcessor(buildMelProcessor());
        dispatcher.addAudioProcessor(buildJLibrosaMelProcessor());

        dispatcherThread = new Thread(dispatcher, "MelSpectrogram-AudioThread");
        dispatcherThread.setDaemon(true);
        dispatcherThread.start();

        Log.i(TAG, String.format("Recording started – %d Hz, %d-pt FFT, "
                        + "%d mel bands, buffer=%d frames (~%.1f s)",
                SAMPLE_RATE, FFT_SIZE, NUM_MEL_BANDS, MAX_FRAMES,
                (double) MAX_FRAMES * HOP_SIZE / SAMPLE_RATE));
    }

    /**
     * Stop recording and release the microphone.
     * Safe to call even when not running.
     */
    public synchronized void stop() {
        if (dispatcher != null) {
            dispatcher.stop();
            dispatcher = null;
        }
        if (dispatcherThread != null) {
            try { dispatcherThread.join(2_000); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            dispatcherThread = null;
        }
        Log.i(TAG, "Recording stopped. Frames in buffer: " + getFrameCount());
        float[] recordingData = flattenRecordingList(recordingBuffer);
        float[][] melSpecData =  jLibrosa.generateMelSpectroGram(recordingData, SAMPLE_RATE, FFT_SIZE, NUM_MEL_BANDS, HOP_SIZE);
        Log.i(TAG, Arrays.toString(melSpecData[0]));
        OnnxTensorInput input = null;//buildTensorNow();
        synchronized (frameLock) {
            if (frameBuffer.isEmpty()) input = null;
            input = packDataToTensor(melSpecData);
        }

        // Run inference when recording stops
        try {
            OnnxTensor t = OnnxTensor.createTensor(modelRunner.getEnvironment(), FloatBuffer.wrap(input.data), input.shape);
            Log.i(TAG, "Inference result: " + modelRunner.runInference(t));
        } catch (OrtException e) {
            throw new RuntimeException(e);
        }
    }

    /** @return true if recording is currently active. */
    public synchronized boolean isRunning() {
        return dispatcher != null;
    }

    /**
     * Returns an immutable snapshot of all frames currently in the buffer,
     * ordered oldest to newest. Safe to call from any thread.
     */
    public List<MelFrame> getFrameSnapshot() {
        synchronized (frameLock) {
            return Collections.unmodifiableList(new ArrayList<>(frameBuffer));
        }
    }

    /** Returns the number of frames currently held in the buffer. */
    public int getFrameCount() {
        synchronized (frameLock) { return frameBuffer.size(); }
    }

    /** Clears the frame buffer without stopping the recorder. */
    public void clearBuffer() {
        synchronized (frameLock) { frameBuffer.clear(); }
        Log.d(TAG, "Frame buffer cleared.");
    }

    /**
     * Builds an OnnxTensorInput from a snapshot of the current buffer on demand.
     * Returns null if the buffer is empty.
     */
    public OnnxTensorInput buildTensorNow() {
        synchronized (frameLock) {
            if (frameBuffer.isEmpty()) return null;
            return packFramesToTensor(new ArrayList<>(frameBuffer));
        }

    }

    // ── DSP processor ─────────────────────────────────────────────────────────

    private long frameCounter = 0; // accessed only on the audio thread

    private AudioProcessor buildMelProcessor() {
        final float[] melBinIndices = computeMelCentreFrequencies(
                NUM_MEL_BANDS, MEL_FREQ_MIN, MEL_FREQ_MAX, SAMPLE_RATE, FFT_SIZE);
        final int numBins = FFT_SIZE / 2 + 1;

        return new AudioProcessor() {

            @Override
            public boolean process(AudioEvent audioEvent) {
                float[] audioBuffer = audioEvent.getFloatBuffer();
                Log.i(TAG, Arrays.toString(audioBuffer));
                if (audioBuffer.length < FFT_SIZE) return true;

                // 1. Hann window
                float[] windowed = new float[FFT_SIZE];
                for (int i = 0; i < FFT_SIZE; i++) {
                    windowed[i] = audioBuffer[i] * hannCoefficients[i];
                }

                // 2. FFT -> power spectrum
                float[] real = windowed.clone();
                float[] imag = new float[FFT_SIZE];
                fft.forwardTransform(real);
                fft.forwardTransform(imag);

                float[] power = new float[numBins];
                for (int k = 0; k < numBins; k++) {
                    power[k] = real[k] * real[k] + imag[k] * imag[k];
                }

                // 3. Triangular mel filter bank
                float[] melEnergies = applyMelFilterBank(power, melBinIndices, numBins);

                // 4. Log (dB) scale
                final float LOG_OFFSET = 1e-10f;
                float[] logMel = new float[NUM_MEL_BANDS];
                for (int m = 0; m < NUM_MEL_BANDS; m++) {
                    logMel[m] = 10f * (float) Math.log10(melEnergies[m] + LOG_OFFSET);
                    //logMel[m] = 10f * (float) Math.log10(audioBuffer[m] + LOG_OFFSET);
                }

                // 5. Build frame and push into circular buffer
                MelFrame frame = new MelFrame(logMel, audioEvent.getTimeStamp(), frameCounter++);
                OnnxTensorInput tensor = null;

                synchronized (frameLock) {
                    if (frameBuffer.size() >= MAX_FRAMES) {
                        frameBuffer.remove(0); // evict oldest frame
                    }
                    frameBuffer.add(frame);

                    // Fire tensor callback every time the buffer is full
                    if (frameBuffer.size() == MAX_FRAMES && tensorListener != null) {
                        tensor = packFramesToTensor(frameBuffer);
                    }
                }

                // 6. Deliver callbacks outside the lock to avoid contention
                if (frameListener != null) frameListener.onMelFrame(frame);
                if (tensor != null)        tensorListener.onTensorReady(tensor);

                return true;
            }

            @Override
            public void processingFinished() {
                Log.d(TAG, "Audio processing finished.");
            }
        };
    }

    private AudioProcessor buildJLibrosaMelProcessor() {


        return new AudioProcessor() {

            @Override
            public boolean process(AudioEvent audioEvent) {
                float[] audioBuffer = audioEvent.getFloatBuffer();
                Log.i(TAG, Integer.toString(audioBuffer.length));
                if (audioBuffer.length < FFT_SIZE) return true;



                // 5. Build frame and push into circular buffer
                OnnxTensorInput tensor = null;

                synchronized (frameLock) {
                    if (recordingBuffer.size() >= MAX_FRAMES) {
                        recordingBuffer.remove(0); // evict oldest frame
                    }
                    recordingBuffer.add(audioBuffer);

                    // Fire tensor callback every time the buffer is full
                    if (recordingBuffer.size() == MAX_FRAMES && tensorListener != null) {
                        //tensor = packFramesToTensor(frameBuffer);
                    }
                }

                // 6. Deliver callbacks outside the lock to avoid contention
                //if (frameListener != null) frameListener.onMelFrame(frame);
                if (tensor != null)        tensorListener.onTensorReady(tensor);

                return true;
            }

            @Override
            public void processingFinished() {
                Log.d(TAG, "Audio processing finished.");
            }
        };
    }

    // ── Tensor packing ────────────────────────────────────────────────────────

    /**
     * Converts a list of mel frames into a flat float tensor.
     *
     * Output layout (row-major):
     *   shape = [1, 1, NUM_MEL_BANDS, numFrames]
     *   data[t * NUM_MEL_BANDS + m] = frame[t].melBands[m]
     *
     * @param frames ordered list of frames (oldest first).
     */
    static OnnxTensorInput packFramesToTensor(List<MelFrame> frames) {
        int numFrames = frames.size();
        float[] data  = new float[MAX_FRAMES * NUM_MEL_BANDS];
        Arrays.fill(data, 0);

        for (int t = 0; t < numFrames; t++) {
            System.arraycopy(frames.get(t).melBands, 0,
                    data, t * NUM_MEL_BANDS, NUM_MEL_BANDS);
        }

        long[] shape = {1L, 1L, (long) NUM_MEL_BANDS, (long) MAX_FRAMES};

        double startTs = frames.get(0).timeStampSeconds;
        double endTs   = frames.get(numFrames - 1).timeStampSeconds;

        return new OnnxTensorInput(data, shape, numFrames, startTs, endTs);
    }

    static OnnxTensorInput packDataToTensor(float[][] melSpecData) {
        int numFrames = melSpecData.length;
        float[] data  = new float[MAX_FRAMES * NUM_MEL_BANDS];
        Arrays.fill(data, 0);

        for (int t = 0; t < MAX_FRAMES; t++) {
            System.arraycopy(melSpecData[t], 0,
                    data, t * NUM_MEL_BANDS, NUM_MEL_BANDS);
        }

        long[] shape = {1L, 1L, (long) NUM_MEL_BANDS, (long) MAX_FRAMES};

        double startTs = 0;
        double endTs   = 1;

        return new OnnxTensorInput(data, shape, numFrames, startTs, endTs);
    }

    private float[] flattenRecordingList(ArrayList<float[]> recordingBuffer) {
        float[] flattenedRecording = new float[MAX_FRAMES*FFT_SIZE];
        for (int i = 0; i < recordingBuffer.size(); i++) {
            float[] recordingFrame = recordingBuffer.get(i);
            System.arraycopy(recordingFrame, 0, flattenedRecording, i*FFT_SIZE, FFT_SIZE);
        }
        Log.i(TAG, "Printing flattened recording");
        Log.i(TAG, Arrays.toString(flattenedRecording));
        return flattenedRecording;
    }

    // ── Mel maths ─────────────────────────────────────────────────────────────

    private static float[] buildHannWindow(int size) {
        float[] w = new float[size];
        for (int i = 0; i < size; i++) {
            w[i] = 0.5f * (1f - (float) Math.cos(2.0 * Math.PI * i / (size - 1)));
        }
        return w;
    }

    private static float hzToMel(float hz) {
        return 2595f * (float) Math.log10(1f + hz / 700f);
    }

    private static float melToHz(float mel) {
        return 700f * ((float) Math.pow(10f, mel / 2595f) - 1f);
    }

    private static float[] computeMelCentreFrequencies(
            int numBands, float fMin, float fMax, int sampleRate, int fftSize) {

        float melMin   = hzToMel(fMin);
        float melMax   = hzToMel(fMax);
        float hzPerBin = (float) sampleRate / fftSize;

        float[] binIndices = new float[numBands + 2];
        for (int i = 0; i < binIndices.length; i++) {
            float mel = melMin + (melMax - melMin) * i / (numBands + 1);
            binIndices[i] = melToHz(mel) / hzPerBin;
        }
        return binIndices;
    }

    private static float[] applyMelFilterBank(float[] power,
                                              float[] binIndices,
                                              int numBins) {
        int numBands     = binIndices.length - 2;
        float[] energies = new float[numBands];

        for (int m = 0; m < numBands; m++) {
            float left   = binIndices[m];
            float centre = binIndices[m + 1];
            float right  = binIndices[m + 2];
            float energy = 0f;

            for (int k = (int) Math.ceil(left); k <= (int) Math.floor(centre) && k < numBins; k++) {
                energy += ((k - left) / (centre - left)) * power[k];
            }
            for (int k = (int) Math.ceil(centre); k <= (int) Math.floor(right) && k < numBins; k++) {
                energy += ((right - k) / (right - centre)) * power[k];
            }
            energies[m] = energy;
        }
        return energies;
    }
}
