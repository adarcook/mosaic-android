package life.mosaic.tensorwhisper;

import android.app.Service;
import android.content.Intent;
import android.os.*;
import java.nio.*;
import java.io.File;
import org.json.JSONObject;

/** Same-signature companion request; native ASR never shares the LLM process/runtime. */
public final class VoiceAsrService extends Service {
    private boolean busy;
    private final Messenger binder = new Messenger(new Handler(Looper.getMainLooper(), msg -> {
        if (msg.what == 9) { android.os.Process.killProcess(android.os.Process.myPid()); return true; }
        if (msg.what == 1 && !busy && msg.replyTo != null) {
            busy = true;
            byte[] pcm = msg.getData().getByteArray("pcm16");
            Messenger reply = msg.replyTo;
            new Thread(() -> run(reply, pcm), "mosaic-voice-asr").start();
        }
        return true;
    }));
    @Override public IBinder onBind(Intent intent) { return binder.getBinder(); }
    @Override public boolean onUnbind(Intent intent) {
        android.os.Process.killProcess(android.os.Process.myPid()); return false;
    }
    private void run(Messenger reply, byte[] pcm) {
        try {
            if (!getSharedPreferences("WhisperGateActivity", 0).getBoolean("crossParityPassedV1", false))
                throw new IllegalStateException("השלם את בדיקת התאימות בשלב 6 באפליקציית התמלול תחילה");
            if (pcm == null || pcm.length < 16000 || pcm.length > 384000 || pcm.length % 2 != 0)
                throw new IllegalArgumentException("נדרשת הקלטה של חצי שנייה עד 12 שניות");
            float[] audio = new float[pcm.length / 2];
            ByteBuffer bytes = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < audio.length; i++) audio[i] = bytes.getShort() / 32768f;
            send(reply, 2, "טוען מנוע תמלול…", null);
            String[] names={"ivrit-whisper-decoder-q5_0.bin","ivrit_whisper_encoder_Google_Tensor_G5.tflite","ivrit_whisper_cross_attention_Google_Tensor_G5.tflite"};
            String[] hashes={"6c1da92e8e41dd64b8cc402eee7eb7a433d2152567e1a4d9cf181fefcc67a572","28541df9741e207c14c3e6b20513fc27a278c5c5835c7fbe01304dbea9057108","fa2d5bb9200d8db59860c5b23dfbe899762f42d269885e2633fc56b18e6306a3"};
            send(reply, 2, "מאמת מודלי תמלול…", null);
            long verifyStart=SystemClock.elapsedRealtime();
            for(int i=0;i<names.length;i++)if(!hashes[i].equals(life.mosaic.models.ModelCopy.sha256(new File(getExternalFilesDir(null),names[i]),()->false)))throw new IllegalStateException("ASR model identity mismatch: "+names[i]);
            long verifyMs=SystemClock.elapsedRealtime()-verifyStart;
            WarmHybridEngine.Result result; long load;
            try (WarmHybridEngine engine = new WarmHybridEngine(getExternalFilesDir(null), getApplicationInfo().nativeLibraryDir)) {
                load = engine.loadMs;
                send(reply, 2, "מתמלל בעברית…", null);
                result = engine.transcribe(audio, 5, phase -> send(reply, 2, phase, null));
            } // Release ASR before signalling that LLM may start.
            if (result.text.isBlank()) throw new IllegalStateException("לא התקבל תמלול");
            Bundle data = new Bundle(); data.putString("transcript", result.text);
            data.putLong("verify_ms",verifyMs);
            data.putLong("load_ms", load); data.putLong("compute_ms", result.totalMs);
            data.putLong("mel_ms", result.melMs); data.putLong("encoder_ms", result.encoderMs);
            data.putLong("cross_ms", result.crossMs); data.putLong("decode_ms", result.decodeMs);
            data.putString("backend", "encoder/cross=NPU; decoder=CPU; beam=5");
            android.os.Debug.MemoryInfo memory=new android.os.Debug.MemoryInfo();android.os.Debug.getMemoryInfo(memory);data.putInt("worker_pss_kb",memory.getTotalPss());data.putInt("thermal_status",((PowerManager)getSystemService(POWER_SERVICE)).getCurrentThermalStatus());
            send(reply, 4, result.text, data);
        } catch (Throwable error) { send(reply, 5, error.toString(), null); }
    }
    private static void send(Messenger reply, int code, String text, Bundle data) {
        Message msg = Message.obtain(null, code); if (data == null) data = new Bundle();
        data.putString("text", text); msg.setData(data);
        try { reply.send(msg); } catch (RemoteException ignored) { android.os.Process.killProcess(android.os.Process.myPid()); }
    }
}
