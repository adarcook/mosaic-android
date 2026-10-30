package life.mosaic.tensorllm;

/** Main-thread retry budget with one pending recovery and cancellable tickets. */
final class RecoveryBudget {
    static final long ALREADY_PENDING=0, EXHAUSTED=-1;
    private final int limit;
    private int attempts;
    private long generation;
    private boolean pending;
    RecoveryBudget(int limit){if(limit<1)throw new IllegalArgumentException("Invalid retry limit");this.limit=limit;}
    long request(){
        if(pending)return ALREADY_PENDING;
        if(attempts>=limit)return EXHAUSTED;
        attempts++;pending=true;return ++generation;
    }
    boolean consume(long ticket){
        if(!pending||ticket!=generation)return false;
        pending=false;return true;
    }
    void cancel(){pending=false;generation++;}
    void reset(){cancel();attempts=0;}
}
