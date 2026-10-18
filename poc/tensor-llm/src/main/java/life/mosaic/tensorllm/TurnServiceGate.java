package life.mosaic.tensorllm;

/** Generation-scoped lifetime: cleanup from an old service cannot cancel a new start. */
final class TurnServiceGate {
    private long generation;
    private boolean wanted,active;
    synchronized long request(){generation++;wanted=true;active=false;return generation;}
    synchronized long current(){return generation;}
    synchronized boolean promote(long request){if(!wanted||request!=generation)return false;active=true;return true;}
    synchronized void cancel(){generation++;wanted=false;active=false;}
    synchronized void destroyed(long request){if(request==generation){wanted=false;active=false;}}
    synchronized boolean expected(){return wanted;}
    synchronized boolean active(){return wanted&&active;}
}
