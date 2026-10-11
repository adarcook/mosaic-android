package life.mosaic.tensorllm;

import android.app.Service;
import android.content.Intent;
import android.media.*;
import android.os.*;
import java.io.*;
import java.nio.*;
import java.util.List;

/** Foreground warm CPU G2P/acoustic worker. Audio is generated from the actual LLM answer. */
public final class TtsService extends Service {
    private volatile boolean busy;
    private BlueVoice voice;private life.mosaic.models.FileSnapshot snapshot;
    private final Messenger incoming=new Messenger(new Handler(Looper.getMainLooper(),msg->{
        if(msg.what==9){android.os.Process.killProcess(android.os.Process.myPid());return true;}
        if(msg.what==1&&!busy&&msg.replyTo!=null){busy=true;Bundle data=new Bundle(msg.getData());Messenger reply=msg.replyTo;new Thread(()->run(reply,data),"mosaic-blue-tts").start();}return true;}));
    @Override public void onTrimMemory(int level){super.onTrimMemory(level);if(level>=TRIM_MEMORY_RUNNING_CRITICAL)android.os.Process.killProcess(android.os.Process.myPid());}
    @Override public IBinder onBind(Intent intent){return incoming.getBinder();}
    @Override public boolean onUnbind(Intent intent){android.os.Process.killProcess(android.os.Process.myPid());return false;}
    private void run(Messenger reply,Bundle request){
        long start=SystemClock.elapsedRealtime(),synthesisMs=0,playMs=0,g2pMs=0,acousticMs=0;long samples=0;Bundle metrics=new Bundle();
        try{
            File root=new File(getExternalFilesDir(null),"blue-voice");boolean reused=voice!=null;
            long verifyMs=0,loadMs=0;
            if(voice==null){
                life.mosaic.models.WarmMemory.beforeLoad(this);
                File[] files=new File[]{new File(root,"manifest.json"),new File(root,"reply.json"),new File(root,"vocab.json"),new File(root,"renikud.onnx"),new File(root,"duration_predictor_style.onnx"),new File(root,"text_encoder.onnx"),new File(root,"vector_estimator.onnx"),new File(root,"vocoder.onnx")};
                life.mosaic.models.FileSnapshot before=new life.mosaic.models.FileSnapshot(files);
                send(reply,2,"מאמת חבילת BlueTTS…",null);TtsPack.verify(root);before.requireUnchanged();verifyMs=SystemClock.elapsedRealtime()-start;
                long loadStart=SystemClock.elapsedRealtime();send(reply,2,"טוען קול והכנת הגייה מקומית…",null);voice=new BlueVoice(root);loadMs=SystemClock.elapsedRealtime()-loadStart;before.requireUnchanged();snapshot=before;
            }else snapshot.requireUnchanged();
            metrics.putBoolean("engine_reused",reused);metrics.putString("verification_mode",reused?"loaded_engine_metadata":"full_sha256");metrics.putLong("verify_ms",verifyMs);metrics.putLong("load_ms",loadMs);
            if(request.getBoolean("prepare",false)){metrics.putString("verification_mode",reused?"loaded_engine_metadata":"full_sha256");metrics.putBoolean("prepared",true);life.mosaic.models.WarmMemory.add(this,metrics);busy=false;send(reply,4,"מנוע הקול מוכן",metrics);return;}
            life.mosaic.models.WarmMemory.beforeLoad(this);voice.resetNoise();String normalized=VoiceText.normalize(request.getString("answer",""));List<String> chunks=VoiceText.chunks(normalized);
                for(int i=0;i<chunks.size();i++){
                    final int chunk=i+1;for(String key:new java.util.ArrayList<>(metrics.keySet()))if(key.startsWith("playback_")||key.equals("chunk_playback_ms"))metrics.remove(key);
                    send(reply,2,"מכין הקראה "+chunk+"/"+chunks.size()+"…",null);long phase=SystemClock.elapsedRealtime();float[] audio=voice.synthesize(chunks.get(i), detail -> send(reply,2,detail+" · "+chunk+"/"+chunks.size(),null));synthesisMs+=SystemClock.elapsedRealtime()-phase;
                    g2pMs+=voice.lastG2pMs;acousticMs+=voice.lastAcousticMs;metrics.putLong("g2p_ms",g2pMs);metrics.putLong("acoustic_ms",acousticMs);metrics.putLong("synthesis_ms",synthesisMs);metrics.putInt("chunk_index",chunk);metrics.putInt("chunk_count",chunks.size());metrics.putInt("chunk_frames",audio.length);metrics.putInt("sample_rate",voice.rate);
                    send(reply,2,"מקריא "+(i+1)+"/"+chunks.size()+"…",new Bundle(metrics));phase=SystemClock.elapsedRealtime();play(audio,voice.rate,metrics);playMs+=SystemClock.elapsedRealtime()-phase;samples+=audio.length;metrics.putLong("playback_ms",playMs);send(reply,2,"מקטע קול הושלם",new Bundle(metrics));
                }
                Bundle data=new Bundle(metrics);data.putLong("verify_ms",verifyMs);data.putLong("load_ms",loadMs);data.putLong("synthesis_ms",synthesisMs);data.putLong("g2p_ms",g2pMs);data.putLong("acoustic_ms",acousticMs);data.putLong("playback_ms",playMs);data.putDouble("audio_seconds",samples/(double)voice.rate);data.putString("normalized_text",normalized);data.putString("backend","BlueTTS 2.5 + RenikudPlus / CPU / 2 threads");
                android.os.Debug.MemoryInfo memory=new android.os.Debug.MemoryInfo();android.os.Debug.getMemoryInfo(memory);data.putInt("worker_pss_kb",memory.getTotalPss());data.putInt("thermal_status",((PowerManager)getSystemService(POWER_SERVICE)).getCurrentThermalStatus());snapshot.requireUnchanged();life.mosaic.models.WarmMemory.add(this,data);busy=false;send(reply,4,"ההקראה הסתיימה",data);
        }catch(Throwable error){busy=false;metrics.putLong("worker_elapsed_ms",SystemClock.elapsedRealtime()-start);send(reply,5,error.toString(),metrics);}
    }
    private void play(float[] audio,int rate,Bundle metrics)throws Exception{
        long playbackStart=SystemClock.elapsedRealtime();
        int min=AudioTrack.getMinBufferSize(rate,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_FLOAT);if(min<1)throw new IOException("Audio output unavailable");
        AudioTrack track=new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()).setAudioFormat(new AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build()).setBufferSizeInBytes(Math.max(min,rate*4)).setTransferMode(AudioTrack.MODE_STREAM).build();
        try{
            if(track.getState()!=AudioTrack.STATE_INITIALIZED)throw new IOException("Audio output failed");
            metrics.putInt("playback_capacity_frames",track.getBufferCapacityInFrames());
            PlaybackLoop.play(audio,rate,new PlaybackLoop.Output(){
                public int start(int requested){int actual=track.setStartThresholdInFrames(requested);metrics.putInt("playback_start_threshold_frames",actual);track.play();return actual;}
                public int write(float[] pcm,int offset,int count){int n=track.write(pcm,offset,count,AudioTrack.WRITE_NON_BLOCKING);metrics.putInt("playback_written_frames",offset+Math.max(0,n));return n;}
                public long head(){return Integer.toUnsignedLong(track.getPlaybackHeadPosition());}
            },new PlaybackLoop.Clock(){
                public long now(){return SystemClock.elapsedRealtime();}
                public void pause()throws InterruptedException{Thread.sleep(10);}
            });
        }finally{
            metrics.putLong("chunk_playback_ms",SystemClock.elapsedRealtime()-playbackStart);
            metrics.putLong("playback_head_frames",Integer.toUnsignedLong(track.getPlaybackHeadPosition()));
            metrics.putInt("playback_underruns",track.getUnderrunCount());metrics.putInt("playback_state",track.getPlayState());
            android.media.AudioDeviceInfo device=track.getRoutedDevice();if(device!=null)metrics.putInt("playback_route_type",device.getType());
            android.util.Log.i("MosaicVoicePlayback", "frames="+audio.length+" rate="+rate+" head="+metrics.getLong("playback_head_frames")+" threshold="+metrics.getInt("playback_start_threshold_frames")+" underruns="+metrics.getInt("playback_underruns"));
            try{track.stop();}catch(RuntimeException ignored){}track.release();
        }
    }
    private static void send(Messenger reply,int code,String text,Bundle data){Message msg=Message.obtain(null,code);if(data==null)data=new Bundle();data.putString("text",text);msg.setData(data);try{reply.send(msg);}catch(RemoteException ignored){android.os.Process.killProcess(android.os.Process.myPid());}}
}
