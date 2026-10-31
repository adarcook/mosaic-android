package life.mosaic.tensorllm;
/** A normal-mode event is a bounded restore trigger, never new consent or a budget reset. */
final class HeadsetResumeGate {
    static boolean allowed(int mode,boolean consent,boolean headset,boolean listening,boolean turn){
        return mode==0&&consent&&headset&&!listening&&!turn;
    }
}
