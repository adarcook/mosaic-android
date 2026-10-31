package life.mosaic.tensorllm;

/** One pending authorized wake waits for FGS promotion; UI resume is not a prerequisite. */
final class BackgroundCaptureStart {
    enum Result { STALE, WAIT, START, ABORT }
    private long generation;
    private boolean pending;
    long request(){if(pending)return 0;pending=true;return ++generation;}
    Result observe(long ticket,boolean authorized,boolean promoted,boolean expired){
        if(!pending||ticket!=generation)return Result.STALE;
        if(!authorized||expired){cancel();return Result.ABORT;}
        if(promoted){pending=false;return Result.START;}
        return Result.WAIT;
    }
    void cancel(){pending=false;generation++;}
}
