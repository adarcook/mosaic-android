package life.mosaic.tensorllm;

import android.content.Context;
import android.media.*;
import android.os.*;

/** Small verified PCM cues; no synthesis/model loading on the interaction path. */
final class VoiceFeedback {
    private final Context context;
    private final Handler main=new Handler(Looper.getMainLooper());
    private volatile int generation;
    private volatile Thread worker;
    private volatile AudioTrack track;
    VoiceFeedback(Context context){this.context=context.getApplicationContext();}
    boolean playing(){Thread current=worker;return current!=null&&current.isAlive();}
    void play(String id,java.util.function.Consumer<Boolean> done){
        cancel();final int token=generation;
        worker=new Thread(()->{
            boolean success=false;AudioTrack output=null;
            try{
                FeedbackClips.Clip clip=FeedbackClips.read(context.getFilesDir(),id);
                int minimum=AudioTrack.getMinBufferSize(clip.rate,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_FLOAT);
                if(minimum<1||token!=generation)return;
                output=new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(clip.rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build())
                    .setBufferSizeInBytes(Math.max(minimum,clip.rate*4)).setTransferMode(AudioTrack.MODE_STREAM).build();
                synchronized(this){if(token!=generation)return;track=output;}
                output.setVolume(1f);final AudioTrack active=output;
                PlaybackLoop.play(clip.pcm,clip.rate,new PlaybackLoop.Output(){
                    public int start(int requested){int value=active.setStartThresholdInFrames(requested);active.play();return value;}
                    public int write(float[] audio,int offset,int count){return active.write(audio,offset,count,AudioTrack.WRITE_NON_BLOCKING);}
                    public long head(){return Integer.toUnsignedLong(active.getPlaybackHeadPosition());}
                },new PlaybackLoop.Clock(){public long now(){return SystemClock.elapsedRealtime();}public void pause()throws InterruptedException{Thread.sleep(10);}});
                success=true;
            }catch(Exception ignored){}
            finally{
                if(output!=null){try{output.stop();}catch(Exception ignored){}output.release();synchronized(this){if(track==output)track=null;}}
                if(Thread.currentThread()==worker)worker=null;
                final boolean result=success;
                main.post(()->{if(token==generation)done.accept(result);});
            }
        },"mosaic-fixed-feedback");worker.start();
    }
    synchronized void cancel(){generation++;Thread previous=worker;worker=null;if(previous!=null)previous.interrupt();AudioTrack output=track;track=null;if(output!=null)try{output.stop();}catch(Exception ignored){}}
}
