package life.mosaic.audio;
/** Version 2 assigns standby mode/focus ownership to the actual recording UID. */
public final class WakeRouteContract {
    public static final int VERSION=2;
    private WakeRouteContract(){}
    public static boolean valid(boolean required,int ownerVersion,int sinkId){return !required||(ownerVersion==VERSION&&sinkId>0);}
}
