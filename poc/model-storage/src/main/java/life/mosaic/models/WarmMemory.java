package life.mosaic.models;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Debug;
import java.io.IOException;

/** Guard against preloading another engine while the phone is already under memory pressure. */
public final class WarmMemory {
    public static void beforeLoad(Context context)throws IOException {
        ActivityManager.MemoryInfo info=new ActivityManager.MemoryInfo();
        ((ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(info);
        if(info.lowMemory||info.availMem<2L*1024*1024*1024)
            throw new IOException("אין מספיק זיכרון לטעינת מנוע; ההכנה נעצרה: available_memory_bytes="+info.availMem+" low_memory="+info.lowMemory);
    }
    public static void beforeInference(Context context)throws IOException {
        ActivityManager.MemoryInfo info=new ActivityManager.MemoryInfo();
        ((ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(info);
        if(info.lowMemory||info.availMem<1024L*1024*1024)throw new IOException("אין מספיק זיכרון לעיבוד; הפעולה נעצרה: available_memory_bytes="+info.availMem+" low_memory="+info.lowMemory);
    }
    public static void add(Context context,Bundle data)throws IOException {
        Debug.MemoryInfo memory=new Debug.MemoryInfo();Debug.getMemoryInfo(memory);
        data.putInt("worker_pss_kb",memory.getTotalPss());
        ActivityManager.MemoryInfo info=new ActivityManager.MemoryInfo();
        ((ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(info);
        data.putLong("available_memory_bytes",info.availMem);
        if(info.lowMemory||info.availMem<1024L*1024*1024)throw new IOException("ההכנה השאירה מעט מדי זיכרון; המנועים ישוחררו");
    }
}
