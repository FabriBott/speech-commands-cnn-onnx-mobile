package com.example.voicesmarthome;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.OrtSession.Result;
import ai.onnxruntime.OrtSession.SessionOptions;
import ai.onnxruntime.OrtSession.SessionOptions.OptLevel;
public class ONNXModelRunner {

    private static final String TAG = "ONNXModelRunner";
    private OrtEnvironment env;
    private OrtSession session;
    private Context context;
    public ONNXModelRunner(Context context) {
        this.context = context;
        try {
            env = OrtEnvironment.getEnvironment();
            String modelPath = copyAssetToInternalStorage(context, "modelo_comandos_voz.onnx");

            session = env.createSession(modelPath);
        } catch (OrtException | IOException e) {
            Log.e(TAG, "Error initializing ONNX model: " + e.getMessage());
            session = null;
        }
    }

    public OrtEnvironment getEnvironment() {
        return env;
    }

    private String copyAssetToInternalStorage(Context context, String assetFileName) throws IOException {
        AssetManager assetManager = context.getAssets();
        InputStream inputStream = assetManager.open(assetFileName);
        File outFile = new File(context.getFilesDir(), assetFileName);
        FileOutputStream outputStream = new FileOutputStream(outFile);
        byte[] buffer = new byte[1024];
        int read;
        while((read = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, read);
        }
        inputStream.close();
        outputStream.close();

        if (!outFile.exists()) {
            return null;
        }
        return outFile.getAbsolutePath();
    }

    public String runInference(OnnxTensor inputTensor) {
        if (session == null) {
            Log.e(TAG, "ONNX session is not initialized");
            return null;
        }

        try {
            Map<String, OnnxTensor> inputMap = Collections.singletonMap("input", inputTensor);
            Result result = session.run(inputMap);

            Optional<OnnxValue> optionalOutput = result.get("last_hidden_state");
            if (optionalOutput.isPresent()) {
                OnnxTensor outputTensor = (OnnxTensor) optionalOutput.get();

                return Integer.toString(decodeOutput(outputTensor));
            } else {
                Log.e(TAG, "No output returned from the model");
                return null;
            }
        } catch (OrtException | IOException e) {
            Log.e(TAG, "Error running ONNX model inference: " + e.getMessage());
            return null;
        }
    }

    //private OnnxTensor prepareInput()

    private int decodeOutput(OnnxTensor outputTensor) throws OrtException, IOException {
        float[][][] outputArray = (float[][][]) outputTensor.getValue();
        for (float[][] sequence: outputArray) {
            for (float[] tokenProbs : sequence) {
                //Find argmax
                int maxIndex = 0;
                float maxValue = tokenProbs[0];

                for (int i = 1; i < tokenProbs.length; i++) {
                    if (tokenProbs[i] > maxValue) {
                        maxValue = tokenProbs[i];
                        maxIndex = i;
                    }
                }

                return maxIndex;
            }
        }
        return -1;
    }
}
