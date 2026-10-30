package life.mosaic.tensorllm;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;

/** Fixed synthetic PCM fixtures only. No microphone, playback, private text or arbitrary prompts. */
public final class WakeFixtureInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){
        Bundle result=new Bundle();
        try{
            File root=new File(getTargetContext().getExternalFilesDir(null),"blue-voice");TtsPack.verify(root);
            File folder=new File(getTargetContext().getCacheDir(),"wake-fixtures-v1");if(!folder.isDirectory()&&!folder.mkdirs())throw new java.io.IOException();
            try(BlueVoice voice=new BlueVoice(root)){
                String[] ids={"wake","music","greeting"};String[] phrases={"מוזאיק","מוזיקה","שלום"};
                for(int i=0;i<ids.length;i++){
                    voice.resetNoise();float[] pcm=voice.synthesize(phrases[i]);
                    int count=(int)((long)pcm.length*16000/voice.rate);ByteBuffer bytes=ByteBuffer.allocate(count*2).order(ByteOrder.LITTLE_ENDIAN);
                    for(int n=0;n<count;n++){double at=(double)n*voice.rate/16000;int a=Math.min(pcm.length-1,(int)at),b=Math.min(pcm.length-1,a+1);float sample=(float)(pcm[a]+(pcm[b]-pcm[a])*(at-a));bytes.putShort((short)(Math.max(-1,Math.min(1,sample))*32767));}
                    Files.write(new File(folder,ids[i]+".pcm").toPath(),bytes.array());
                }
            }
            result.putBoolean("synthetic_fixtures_created",true);result.putBoolean("microphone_used",false);finish(Activity.RESULT_OK,result);
        }catch(Throwable failure){result.putBoolean("synthetic_fixtures_created",false);finish(Activity.RESULT_CANCELED,result);}
    }
}
