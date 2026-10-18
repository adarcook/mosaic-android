package life.mosaic.tensorllm;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import com.google.ai.edge.litertlm.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/** Debug-only, fixed synthetic fixtures. It cannot read notifications or accept arbitrary prompts. */
public final class AgentProbeInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Engine engine=null;Bundle result=new Bundle();JSONArray cases=new JSONArray();
        try{
            Context context=getTargetContext();
            File model=new File(context.getExternalFilesDir(null),ModelSpec.FILE);
            File dispatch=new File(context.getApplicationInfo().nativeLibraryDir,"libLiteRtDispatch_GoogleTensor.so");
            progress("verify",0);
            if(model.length()!=ModelSpec.BYTES||!ModelSpec.SHA256.equals(hash(model))||!ModelSpec.DISPATCH_SHA256.equals(hash(dispatch)))
                throw new IOException("Fixture runtime identity mismatch");
            engine=new Engine(new EngineConfig(model.getAbsolutePath(),new Backend.NPU(context.getApplicationInfo().nativeLibraryDir),
                    null,null,null,null,context.getCacheDir().getAbsolutePath()));
            progress("load",0);engine.initialize();
            String[] ids={"greeting","read_whatsapp","personal_alias","negated","explanation"};
            String[] requests={"שלום, מי אתה?","תקריא לי את הודעת הוואטסאפ האחרונה","תן לי עדכון",
                    "אל תקריא לי את הודעת הוואטסאפ האחרונה","איך אפשר להקריא הודעות בוואטסאפ?"};
            boolean[] expected={false,true,true,false,false};
            boolean passed=true;
            for(int i=0;i<ids.length;i++){
                progress(ids[i],i+1);
                Conversation conversation=null;AtomicInteger calls=new AtomicInteger();JSONObject item=new JSONObject().put("case",ids[i]);
                try{
                    String hint=ids[i].equals("personal_alias")?AgentTools.WHATSAPP_FUNCTION:"";
                    conversation=engine.createConversation(new ConversationConfig(Contents.Companion.of(AgentTools.system(hint)),
                            Collections.emptyList(),NativeAgentModel.providers(),new SamplerConfig(20,0.9,0.2,0),false,
                            Collections.emptyList(),Collections.emptyMap(),null,false,512,new ThinkingConfig(false),false,false,null));
                    NativeAgentModel nativeModel=new NativeAgentModel(conversation,SystemClock.elapsedRealtime()+60000,hint);
                    String answer=ManualToolAgent.run(requests[i],nativeModel,(name,args)->{
                        calls.incrementAndGet();return new VoiceTool.Result(VoiceTool.Status.NO_MESSAGE,
                                "לא נמצאה הודעת וואטסאפ זמינה בהתראות. אין גישה להיסטוריית השיחות.","לא נמצאה הודעה");
                    },()->false);
                    boolean ok=calls.get()==(expected[i]?1:0);
                    item.put("passed",ok).put("fixture_tool_calls",calls.get()).put("answer",answer);
                    passed&=ok;
                }catch(Throwable failure){item.put("passed",false).put("fixture_tool_calls",calls.get()).put("error","probe_failed");passed=false;}
                finally{if(conversation!=null)try{conversation.close();}catch(Throwable ignored){}}
                cases.put(item);
            }
            result.putString("cases",cases.toString());result.putBoolean("passed",passed);result.putBoolean("real_notifications_read",false);
            finish(passed?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
        }catch(Throwable error){result.putBoolean("passed",false);result.putString("error","runtime_probe_failed");finish(Activity.RESULT_CANCELED,result);}
        finally{if(engine!=null)try{engine.close();}catch(Throwable ignored){}}
    }
    private void progress(String phase,int index){Bundle status=new Bundle();status.putString("phase",phase);status.putInt("case_index",index);sendStatus(0,status);}
    private static String hash(File file)throws Exception{
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream input=new FileInputStream(file)){byte[] buffer=new byte[1024*1024];int count;while((count=input.read(buffer))!=-1)digest.update(buffer,0,count);}
        return TranscriptSource.hex(digest.digest());
    }
}
