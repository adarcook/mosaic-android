package life.mosaic.tensorllm;

import android.app.Service;
import android.content.Intent;
import android.media.*;
import android.os.*;
import java.io.*;
import java.nio.*;
import java.util.List;

/** Disposable CPU G2P/acoustic worker. Audio is generated from the actual LLM answer. */
public final class TtsService extends Service {
    private boolean busy;
    private final Messenger incoming=new Messenger(new Handler(Looper.getMainLooper(),msg->{
        if(msg.what==9){android.os.Process.killProcess(android.os.Process.myPid());return true;}
        if(msg.what==1&&!busy&&msg.replyTo!=null){busy=true;Bundle data=new Bundle(msg.getData());Messenger reply=msg.replyTo;new Thread(()->run(reply,data),"mosaic-blue-tts").start();}return true;}));
    @Override public IBinder onBind(Intent intent){return incoming.getBinder();}
    @Override public boolean onUnbind(Intent intent){android.os.Process.killProcess(android.os.Process.myPid());return false;}
    private void run(Messenger reply,Bundle request){
        long start=SystemClock.elapsedRealtime(),synthesisMs=0,playMs=0,g2pMs=0,acousticMs=0;long samples=0;
        try{
            String normalized=VoiceText.normalize(request.getString("answer",""));List<String> chunks=VoiceText.chunks(normalized);
            File root=new File(getExternalFilesDir(null),"blue-voice");send(reply,2,"מאמת חבילת BlueTTS…",null);TtsPack.verify(root);
            long verifyMs=SystemClock.elapsedRealtime()-start,loadStart=SystemClock.elapsedRealtime();
            send(reply,2,"טוען קול והכנת הגייה מקומית…",null);
            try(BlueVoice voice=new BlueVoice(root)){
                long loadMs=SystemClock.elapsedRealtime()-loadStart;
                for(int i=0;i<chunks.size();i++){
                    send(reply,2,"מכין הקראה "+(i+1)+"/"+chunks.size()+"…",null);long phase=SystemClock.elapsedRealtime();float[] audio=voice.synthesize(chunks.get(i));synthesisMs+=SystemClock.elapsedRealtime()-phase;
                    g2pMs+=voice.lastG2pMs;acousticMs+=voice.lastAcousticMs;
                    send(reply,2,"מקריא "+(i+1)+"/"+chunks.size()+"…",null);phase=SystemClock.elapsedRealtime();play(audio,voice.rate);playMs+=SystemClock.elapsedRealtime()-phase;samples+=audio.length;
                }
                Bundle data=new Bundle();data.putLong("verify_ms",verifyMs);data.putLong("load_ms",loadMs);data.putLong("synthesis_ms",synthesisMs);data.putLong("g2p_ms",g2pMs);data.putLong("acoustic_ms",acousticMs);data.putLong("playback_ms",playMs);data.putDouble("audio_seconds",samples/(double)voice.rate);data.putString("normalized_text",normalized);data.putString("backend","BlueTTS 2.5 + RenikudPlus / CPU / 2 threads");
                android.os.Debug.MemoryInfo memory=new android.os.Debug.MemoryInfo();android.os.Debug.getMemoryInfo(memory);data.putInt("worker_pss_kb",memory.getTotalPss());data.putInt("thermal_status",((PowerManager)getSystemService(POWER_SERVICE)).getCurrentThermalStatus());send(reply,4,"ההקראה הסתיימה",data);
            }
        }catch(Throwable error){send(reply,5,error.toString(),null);}
    }
    private void play(float[] audio,int rate)throws Exception{
        int min=AudioTrack.getMinBufferSize(rate,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_FLOAT);if(min<1)throw new IOException("Audio output unavailable");
        AudioTrack track=new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()).setAudioFormat(new AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build()).setBufferSizeInBytes(Math.max(min,rate*4)).setTransferMode(AudioTrack.MODE_STREAM).build();
        try{if(track.getState()!=AudioTrack.STATE_INITIALIZED)throw new IOException("Audio output failed");track.play();int offset=0;while(offset<audio.length){int n=track.write(audio,offset,Math.min(4096,audio.length-offset),AudioTrack.WRITE_BLOCKING);if(n<=0)throw new IOException("Audio write failed: "+n);offset+=n;}
            long deadline=SystemClock.elapsedRealtime()+(long)(audio.length*1000.0/rate)+3000;while(Integer.toUnsignedLong(track.getPlaybackHeadPosition())<audio.length){if(SystemClock.elapsedRealtime()>deadline)throw new IOException("Playback timeout");Thread.sleep(20);}
        }finally{try{track.stop();}catch(RuntimeException ignored){}track.release();}
    }
    private static void send(Messenger reply,int code,String text,Bundle data){Message msg=Message.obtain(null,code);if(data==null)data=new Bundle();data.putString("text",text);msg.setData(data);try{reply.send(msg);}catch(RemoteException ignored){android.os.Process.killProcess(android.os.Process.myPid());}}
}
