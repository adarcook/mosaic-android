package life.mosaic.tensorwhisper;

/** Conservative exact token overlap. Raw segment texts remain in the session journal. */
final class StreamText {
    static String append(String previous, String next) {
        if (next.trim().isEmpty()) return previous;
        if (previous.trim().isEmpty()) return next.trim();
        String[] left = previous.trim().split("\\s+"), right = next.trim().split("\\s+");
        int overlap = 0;
        for (int n = Math.min(12, Math.min(left.length, right.length)); n >= 2; n--) {
            boolean match = true;
            for (int i = 0; i < n; i++) if (!left[left.length - n + i].equals(right[i])) match = false;
            if (match) { overlap = n; break; }
        }
        StringBuilder result = new StringBuilder(previous.trim());
        for (int i = overlap; i < right.length; i++) result.append(' ').append(right[i]);
        return result.toString();
    }
}
