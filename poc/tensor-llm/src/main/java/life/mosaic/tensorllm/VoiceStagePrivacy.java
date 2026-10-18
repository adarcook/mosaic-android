package life.mosaic.tensorllm;

/** IPC payload fields are not diagnostic metadata. */
final class VoiceStagePrivacy {
    static boolean journalField(String key, boolean toolTurn) {
        return !key.equals("text") && !key.startsWith("agent_") &&
                !(toolTurn && (key.equals("answer") || key.equals("normalized_text")));
    }
}
