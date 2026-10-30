package life.mosaic.tensorllm;

import android.content.Context;

/** Durable owner choice, separate from transient listener/turn process state. */
final class ListeningIntent {
    interface Backend { boolean read(); boolean write(boolean enabled); }
    private final Backend backend;
    ListeningIntent(Backend backend){this.backend=backend;}
    boolean enabled(){return backend.read();}
    boolean set(boolean enabled){return backend.write(enabled);}
    static ListeningIntent open(Context c){
        android.content.SharedPreferences preferences=c.getSharedPreferences("handsfree",0);
        return new ListeningIntent(new Backend(){
            public boolean read(){return preferences.getBoolean("always_listen",false);}
            public boolean write(boolean enabled){return preferences.edit().putBoolean("always_listen",enabled).commit();}
        });
    }
}
