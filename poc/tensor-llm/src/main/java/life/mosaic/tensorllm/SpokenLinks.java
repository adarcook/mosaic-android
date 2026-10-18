package life.mosaic.tensorllm;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Speech-only URL placeholders. Never resolves, follows or logs a link. */
final class SpokenLinks {
    private static final String TAIL="(?:(?![,;:!?][א-ת])[^\\s<>\"׳״'])";
    private static final Pattern URL=Pattern.compile(
        "https?://"+TAIL+"+|(?<![a-z0-9_.@-])www\\."+TAIL+"+"+
        "|(?<![a-z0-9_.@-])(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}"+
        "(?::[0-9]{1,5})?(?:[/\\?#]"+TAIL+"*)?",Pattern.CASE_INSENSITIVE);
    private static final String PLACEHOLDER="קישור מצורף";
    static String forSpeech(String text){
        Matcher matches=URL.matcher(text);StringBuffer out=new StringBuffer();
        while(matches.find()){
            String found=matches.group();int end=found.length();
            while(end>0&&".,!?;:)]}".indexOf(found.charAt(end-1))>=0)end--;
            matches.appendReplacement(out,Matcher.quoteReplacement(PLACEHOLDER+found.substring(end)));
        }
        matches.appendTail(out);
        String speech=out.toString().replaceAll("קישור מצורף(?:\\s+קישור מצורף)+","קישורים מצורפים");
        // Many tiny domains can expand when replaced; keep the existing bounded TTS input.
        return speech.length()>1100?speech.replace(PLACEHOLDER,"קישור"):speech;
    }
    private SpokenLinks(){}
}
