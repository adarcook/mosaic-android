package life.mosaic.tensorwhisper;

import android.app.Service;
import android.content.Intent;
import android.os.*;

import com.google.ai.edge.litert.*;

import java.util.*;
import java.util.concurrent.Executors;

/**
 * Executes the Whisper encoder in a private disposable process.
 *
 * The compiled model must be produced by the authorized Tensor SDK and packaged
 * as assets/whisper_tiny_30s_Google_Tensor_G5.tflite.
 */
public final class WhisperGateService extends Service {
    private static final int N_MELS = 80;
    private static final int N_FRAMES = 3000;
    private static final int INPUT_FLOATS = N_MELS * N_FRAMES;

    private boolean started;
    private final Handler main = new Handler(Looper.getMainLooper());

    private final Messenger binder = new Messenger(new Handler(Looper.getMainLooper(), msg -> {
        if (msg.what == 9) {
            android.os.Process.killProcess(android.os.Process.myPid());
            return true;
        }
        if (msg.what != 1 || started || msg.replyTo == null) return true;
        started = true;

        Messenger reply = msg.replyTo;
        Message pid = Message.obtain(null, 1);
        pid.arg1 = android.os.Process.myPid();
        try {
            reply.send(pid);
        } catch (RemoteException e) {
            android.os.Process.killProcess(android.os.Process.myPid());
            return true;
        }

        Executors.newSingleThreadExecutor().execute(() -> runGate(reply));
        return true;
    }));

    private void send(Messenger reply, int what, String text) {
        Message msg = Message.obtain(null, what);
        Bundle data = new Bundle();
        data.putString("text", text);
        msg.setData(data);
        try {
            reply.send(msg);
        } catch (RemoteException e) {
            android.os.Process.killProcess(android.os.Process.myPid());
        }
    }

    private void runGate(Messenger reply) {
        String result;
        try (Environment env = Environment.create(Collections.singletonMap(
                Environment.Option.DispatchLibraryDir,
                getApplicationInfo().nativeLibraryDir))) {

            Set<Accelerator> available = env.getAvailableAccelerators();
            send(reply, 2, "מאיצים זמינים: " + available);
            if (!available.contains(Accelerator.NPU)) {
                throw new IllegalStateException("NPU unavailable: " + available);
            }

            long createStart = SystemClock.elapsedRealtime();
            try (CompiledModel model = CompiledModel.create(
                    getAssets(),
                    "whisper_tiny_30s_Google_Tensor_G5.tflite",
                    new CompiledModel.Options(Accelerator.NPU),
                    env)) {

                long loadMs = SystemClock.elapsedRealtime() - createStart;
                send(reply, 2, "מודל Whisper AOT נטען ב-" + loadMs + " ms");

                List<TensorBuffer> inputs = model.createInputBuffers(0);
                List<TensorBuffer> outputs = new ArrayList<>();
                try {
                    outputs = model.createOutputBuffers(0);

                    if (inputs.size() != 1) {
                        throw new IllegalStateException(
                                "Encoder graph expected 1 input, got " + inputs.size());
                    }
                    if (outputs.isEmpty()) {
                        throw new IllegalStateException("Encoder graph returned no outputs");
                    }

                    // Whisper Tiny encode signature uses [1, 80, 3000] log-mel.
                    // Zeros are intentional: this is a deterministic backend gate,
                    // not an accuracy test and contains no user audio.
                    inputs.get(0).writeFloat(new float[INPUT_FLOATS]);

                    send(reply, 2, "מריץ encoder אמיתי (80×3000 log-mel)…");
                    long runStart = SystemClock.elapsedRealtime();
                    model.run(inputs, outputs, 0);
                    long runMs = SystemClock.elapsedRealtime() - runStart;

                    float[] encoded = outputs.get(0).readFloat();
                    if (encoded.length == 0) {
                        throw new IllegalStateException("Empty encoder output");
                    }

                    int finite = 0;
                    double checksum = 0.0;
                    int stride = Math.max(1, encoded.length / 4096);
                    for (int i = 0; i < encoded.length; i += stride) {
                        float v = encoded[i];
                        if (Float.isFinite(v)) {
                            finite++;
                            checksum += v;
                        }
                    }
                    if (finite == 0 || !Double.isFinite(checksum)) {
                        throw new IllegalStateException("Encoder output is not finite");
                    }

                    result =
                            "PASS — Whisper encoder רץ דרך נתיב NPU/TPU\n" +
                            "זמן טעינה: " + loadMs + " ms\n" +
                            "זמן encoder: " + runMs + " ms\n" +
                            "output floats: " + encoded.length + "\n" +
                            "sample checksum: " + checksum + "\n\n" +
                            "זה עדיין gate בלבד. PASS מאפשר לחבר את אותו backend " +
                            "למיקרופון ול-decoder העברי בשלב הבא.";
                } finally {
                    for (TensorBuffer b : outputs) b.close();
                    for (TensorBuffer b : inputs) b.close();
                }
            }
        } catch (Throwable e) {
            result = "FAIL — " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }

        send(reply, 3, result);
        main.postDelayed(() -> android.os.Process.killProcess(android.os.Process.myPid()), 500);
    }

    @Override public IBinder onBind(Intent intent) {
        return binder.getBinder();
    }

    @Override public boolean onUnbind(Intent intent) {
        android.os.Process.killProcess(android.os.Process.myPid());
        return false;
    }
}
