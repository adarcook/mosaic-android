package life.mosaic.tensorllm;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.json.JSONObject;

/** Accept only a complete archive with a contiguous committed prefix. Never rewrite ASR text. */
final class TranscriptSource {
    final String text, sha256;
    TranscriptSource(String text, String sha256) { this.text = text; this.sha256 = sha256; }
    static TranscriptSource read(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192]; int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() + count > 4 * 1024 * 1024) throw new IOException("קובץ התמלול גדול מדי לניסוי");
            output.write(buffer, 0, count);
        }
        byte[] bytes = output.toByteArray();
        if (bytes.length == 0 || bytes[bytes.length - 1] != '\n') throw new IOException("יומן תמלול שנקטע");
        String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\n");
        JSONObject header = new JSONObject(lines[0]);
        if (!"start".equals(header.getString("type")) || header.getInt("version") != 3
                || !"archive".equals(header.getString("mode")) || header.getInt("window_s") != 12
                || header.getInt("overlap_s") != 1 || !"s16le_mono_16000".equals(header.getString("pcm_format")))
            throw new IOException("נדרש יומן ארכיון משלב 9");
        long captured = -1, processed = 0; int index = 0; boolean complete = false;
        StringBuilder text = new StringBuilder();
        for (int i = 1; i < lines.length; i++) {
            JSONObject event = new JSONObject(lines[i]); String type = event.getString("type");
            if (type.equals("capture_end")) captured = event.getLong("captured_samples");
            if (type.equals("resume")) complete = false;
            if (type.equals("segment")) {
                long start = event.getLong("start_sample"), end = event.getLong("end_sample");
                if (captured <= 0 || complete || event.getInt("index") != index || start != index * 176000L
                        || end != Math.min(captured, start + 192000L) || end <= processed
                        || event.getInt("rate") != 16000
                        || event.getInt("overlap_samples") != (index == 0 ? 0 : 16000)
                        || event.getInt("beam_size") != header.getInt("beam_size"))
                    throw new IOException("מקטעים כפולים, חסרים או לא תואמים");
                text.append("[מקטע ").append(index + 1).append("] ").append(event.getString("text")).append('\n');
                processed = end; index++;
            }
            if (type.equals("end")) {
                complete = captured > 0 && processed == captured && "complete".equals(event.getString("state"))
                        && event.getLong("captured_samples") == captured
                        && event.getLong("processed_end_sample") == processed && event.getLong("unprocessed_samples") == 0
                        && event.optString("error", "").isEmpty();
            }
        }
        if (!complete || !new JSONObject(lines[lines.length - 1]).getString("type").equals("end"))
            throw new IOException("יש להשלים את התמלול לפני שליחה למודל");
        if (text.length() > ModelSpec.MAX_TEXT) throw new IOException("התמליל ארוך מדי לניסוי הראשון; אין קיצור אוטומטי");
        return new TranscriptSource(text.toString(), hex(MessageDigest.getInstance("SHA-256").digest(bytes)));
    }
    static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(); for (byte b : bytes) result.append(String.format("%02x", b & 255));
        return result.toString();
    }
}
