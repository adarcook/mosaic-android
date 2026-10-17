package life.mosaic.tensorllm;

import java.util.List;

/** Pure, bounded read-only tool contract. No notification text is persisted by this class. */
public final class WhatsAppNotificationTool {
    public static final String ID = "notifications.read_latest_whatsapp";
    public enum Status { OK, PERMISSION_DENIED, LISTENER_UNAVAILABLE, DEVICE_LOCKED, NO_MESSAGE, CONTENT_UNAVAILABLE, READ_ERROR }

    public static final class Snapshot {
        public final String packageName, title, text;
        public final long postedAtMillis;
        public final boolean groupSummary, messageCandidate;
        public Snapshot(String packageName, String title, String text, long postedAtMillis,
                        boolean groupSummary, boolean messageCandidate) {
            this.packageName=packageName;this.title=title;this.text=text;
            this.postedAtMillis=postedAtMillis;this.groupSummary=groupSummary;this.messageCandidate=messageCandidate;
        }
    }
    public static final class Result {
        public final Status status;
        public final String title, text;
        public final long postedAtMillis;
        private Result(Status status,String title,String text,long postedAtMillis){
            this.status=status;this.title=title;this.text=text;this.postedAtMillis=postedAtMillis;
        }
        public boolean found(){return status==Status.OK;}
    }
    private WhatsAppNotificationTool(){}
    public static Result unavailable(Status status) {
        if(status==Status.OK)throw new IllegalArgumentException("OK needs message content");
        return new Result(status,null,null,0);
    }
    public static Result selectLatest(List<Snapshot> notifications) {
        if(notifications==null||notifications.isEmpty())return unavailable(Status.NO_MESSAGE);
        Snapshot newest=null;
        for(Snapshot item:notifications){
            if(item==null||!"com.whatsapp".equals(item.packageName)||item.groupSummary||!item.messageCandidate)continue;
            if(newest==null||item.postedAtMillis>newest.postedAtMillis)newest=item;
        }
        if(newest==null)return unavailable(Status.NO_MESSAGE);
        String text=clean(newest.text,850);
        if(text.isEmpty())return unavailable(Status.CONTENT_UNAVAILABLE);
        return new Result(Status.OK,clean(newest.title,96),text,newest.postedAtMillis);
    }
    private static String clean(String raw,int max){
        if(raw==null)return "";
        String normalized=raw.replaceAll("[\\p{Cc}\\p{Cf}]+"," ").replaceAll("\\s+"," ").trim();
        if(normalized.length()<=max)return normalized;
        return normalized.substring(0,max).trim()+"…";
    }
    /** Speak only bounded locally returned text; unavailable states never invent content. */
    public static String spoken(Result result){
        return switch(result.status){
            case OK -> "בהתראה האחרונה בוואטסאפ"+(result.title.isEmpty()?"":", "+result.title)+", נכתב: "+result.text;
            case PERMISSION_DENIED -> "אין לי הרשאה לקרוא התראות. אפשר לאשר את הגישה בהגדרות אנדרואיד.";
            case LISTENER_UNAVAILABLE -> "שירות ההתראות עדיין לא מחובר. נסה שוב בעוד רגע.";
            case DEVICE_LOCKED -> "הטלפון נעול. פתח אותו לפני שאקריא הודעה פרטית.";
            case NO_MESSAGE -> "לא מצאתי כרגע הודעת וואטסאפ בהתראות הזמינות. אין לי גישה להיסטוריית השיחות.";
            case CONTENT_UNAVAILABLE -> "יש התראת וואטסאפ, אבל תוכן ההודעה אינו זמין לקריאה.";
            case READ_ERROR -> "לא הצלחתי לקרוא את ההתראות כרגע.";
        };
    }
}
