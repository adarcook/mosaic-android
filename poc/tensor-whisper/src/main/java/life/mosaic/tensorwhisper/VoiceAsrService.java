package life.mosaic.tensorwhisper;

import android.app.Service;
import android.content.Intent;
import android.os.*;
import java.nio.*;
import java.io.File;
import org.json.JSONObject;

/** Same-signature warm foreground companion; native ASR stays in its own process. */
public final class VoiceAsrService extends Service {
    private volatile boolean busy;
    private WarmHybridEngine engine;private life.mosaic.models.FileSnapshot snapshot;
    private final Messenger binder = new Messenger(new Handler(Looper.getMainLooper(), msg -> {
        if (msg.what == 9) { android.os.Process.killProcess(android.os.Process.myPid()); return true; }
        if (msg.what == 1 && !busy && msg.replyTo != null) {
            busy = true;
            byte[] pcm = msg.getData().getByteArray("pcm16");boolean prepare=msg.getData().getBoolean("prepare",false);
            Messenger reply = msg.replyTo;String session=msg.getData().getString("foreground_session", "");
            int beamSize = msg.getData().getInt("beam_size", 5);
            new Thread(() -> run(reply, pcm, prepare, session, beamSize), "mosaic-voice-asr").start();
        }
        return true;
    }));
    @Override public void onTrimMemory(int level){super.onTrimMemory(level);if(level>=TRIM_MEMORY_RUNNING_CRITICAL)android.os.Process.killProcess(android.os.Process.myPid());}
    @Override public IBinder onBind(Intent intent) { return binder.getBinder(); }
    @Override public boolean onUnbind(Intent intent) {
        android.os.Process.killProcess(android.os.Process.myPid()); return false;
    }
    private void run(Messenger reply, byte[] pcm, boolean prepare, String session, int beamSize) {
        try {
            // Fail closed on unsupported experiment values; older callers default to Beam 5.
            if (beamSize != 2 && beamSize != 5) throw new IllegalArgumentException("Unsupported ASR beam size: " + beamSize);
            if (!getSharedPreferences("WhisperGateActivity", 0).getBoolean("crossParityPassedV1", false))
                throw new IllegalStateException("השלם את בדיקת התאימות בשלב 6 באפליקציית התמלול תחילה");
            send(reply, 2, "טוען מנוע תמלול…", null);
            String[] names={"ivrit-whisper-decoder-q5_0.bin","ivrit_whisper_encoder_Google_Tensor_G5.tflite","ivrit_whisper_cross_attention_Google_Tensor_G5.tflite"};
            String[] hashes={"6c1da92e8e41dd64b8cc402eee7eb7a433d2152567e1a4d9cf181fefcc67a572","28541df9741e207c14c3e6b20513fc27a278c5c5835c7fbe01304dbea9057108","fa2d5bb9200d8db59860c5b23dfbe899762f42d269885e2633fc56b18e6306a3"};
            long verifyMs=0,load=0;boolean reused=engine!=null, verificationCached=false;
            if(engine==null){
                life.mosaic.models.WarmMemory.beforeLoad(this);
                send(reply,2,"מאמת מודלי תמלול…",null);long verifyStart=SystemClock.elapsedRealtime();
                File[] files=new File[names.length];for(int i=0;i<names.length;i++)files[i]=new File(getExternalFilesDir(null),names[i]);
                life.mosaic.models.FileSnapshot before=new life.mosaic.models.FileSnapshot(files);
                verificationCached=life.mosaic.models.SessionVerification.verify(new File(getFilesDir(),"voice-asr-verification.properties"),session,String.join("|",hashes),files,()->{for(int i=0;i<names.length;i++)if(!hashes[i].equals(life.mosaic.models.ModelCopy.sha256(files[i],()->false)))throw new IllegalStateException("ASR model identity mismatch: "+names[i]);});
                before.requireUnchanged();verifyMs=SystemClock.elapsedRealtime()-verifyStart;
                engine=new WarmHybridEngine(getExternalFilesDir(null),getApplicationInfo().nativeLibraryDir);load=engine.loadMs;
                before.requireUnchanged();snapshot=before;
            }else snapshot.requireUnchanged();
            if(prepare){Bundle ready=new Bundle();ready.putString("verification_mode",reused?"loaded_engine_metadata":verificationCached?"session_receipt_metadata":"full_sha256");ready.putBoolean("prepared",true);ready.putBoolean("engine_reused",reused);ready.putLong("verify_ms",verifyMs);ready.putLong("load_ms",load);life.mosaic.models.WarmMemory.add(this,ready);busy=false;send(reply,4,"מנוע התמלול מוכן",ready);return;}
            life.mosaic.models.WarmMemory.beforeInference(this);
            if(pcm==null||pcm.length<16000||pcm.length>384000||pcm.length%2!=0)throw new IllegalArgumentException("נדרשת הקלטה של חצי שנייה עד 12 שניות");
            float[] audio=new float[pcm.length/2];ByteBuffer bytes=ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);for(int i=0;i<audio.length;i++)audio[i]=bytes.getShort()/32768f;
            send(reply,2,"מתמלל בעברית — מנוע מוכן…",null);
            WarmHybridEngine.Result result=engine.transcribe(audio,beamSize,phase->send(reply,2,phase,null));
            if (result.text.isBlank()) throw new IllegalStateException("לא התקבל תמלול");
            Bundle data = new Bundle(); data.putString("transcript", result.text);
            data.putLong("verify_ms",verifyMs);data.putBoolean("engine_reused",reused);data.putString("verification_mode",reused?"loaded_engine_metadata":verificationCached?"session_receipt_metadata":"full_sha256");
            data.putLong("load_ms", load); data.putLong("compute_ms", result.totalMs);
            data.putLong("mel_ms", result.melMs); data.putLong("encoder_ms", result.encoderMs);
            data.putLong("cross_ms", result.crossMs); data.putLong("decode_ms", result.decodeMs);
            data.putInt("beam_size", beamSize);
            data.putString("backend", "encoder/cross=NPU; decoder=CPU; beam=" + beamSize);
            android.os.Debug.MemoryInfo memory=new android.os.Debug.MemoryInfo();android.os.Debug.getMemoryInfo(memory);data.putInt("worker_pss_kb",memory.getTotalPss());data.putInt("thermal_status",((PowerManager)getSystemService(POWER_SERVICE)).getCurrentThermalStatus());
            snapshot.requireUnchanged();life.mosaic.models.WarmMemory.add(this,data);busy=false;send(reply, 4, result.text, data);
        } catch (Throwable error) { busy=false;send(reply, 5, error.toString(), null); }
    }
    private static void send(Messenger reply, int code, String text, Bundle data) {
        Message msg = Message.obtain(null, code); if (data == null) data = new Bundle();
        data.putString("text", text); msg.setData(data);
        try { reply.send(msg); } catch (RemoteException ignored) { android.os.Process.killProcess(android.os.Process.myPid()); }
    }
}
