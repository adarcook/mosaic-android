# Voice 0.23 — Local personal command dictionary

2026-10-18. Stacked on draft #50 (`bd9217af3a62880d89a3678e3a6cffeac593d99f`),
which extracts tool routing from the approved Voice 0.22/main baseline
`613b71ebd3d94dcb7e84f70af058a818739fb3f9`. Neither this slice nor #50 is
merged. No roadmap stage is completed by adding the dictionary.

## Using it

On the conversation screen, tap **המילון האישי**, below notification access.
The screen lists saved phrases, their action and enabled state. Tap **הוספת ביטוי**,
enter a phrase, review the action **הקראת התראת WhatsApp האחרונה**, and tap
**שמירה**. An empty dictionary offers “תן לי עדכון” and “מה שלחו לי?” as editable
examples; nothing is activated until the user saves. Each row has an on/off switch,
**עריכה** and **מחיקה**. Deletion asks for confirmation. Edits apply to the next
request without app restart. The editor requires an unlocked device and cannot be
opened during an active voice turn or foreground-service probe.

The sole action is the existing read-only latest accessible WhatsApp notification
tool. There is no sender selection, full chat history, send/reply/delete action,
remote command, or new permission. Permission and keyguard checks run in the native
notification adapter at execution time, even when an alias selected the tool.

Phrase matching compares the entire normalized transcript. It ignores case,
Hebrew vocalization, punctuation and extra whitespace; it does not perform fuzzy,
substring or semantic matching. For example “תן לי עדכון!” matches the saved phrase,
but “אל תן לי עדכון” or “תן לי עדכון אחר כך” does not. The shared command normalizer
keeps Hebrew punctuation such as maqaf as word boundaries, rather than joining a
negation to its following word. Existing explicit WhatsApp requests still work.
Disabling/deleting an alias removes only that mapping; it does not disable built-in
requests. General unmatched conversation continues through Gemma.

## Data and routing boundary

`PersonalCommands` is an immutable, bounded snapshot: at most 32 entries, each with
a stable ID, 2–96 character phrase, one allowlisted tool ID and enabled flag.
Normalized duplicate phrases (including disabled ones), duplicate IDs, unknown tool
IDs, empty/oversized phrases, and negation/explanation/unsupported-action phrases
are rejected. Unknown schema versions, oversized or corrupt snapshots fail closed
with a content-free error; they are not silently overwritten or treated as empty.

`PersonalCommandStore` uses one versioned JSON file in private app storage, written
with Android `AtomicFile`, explicit sync and read-back confirmation. There is no
dictionary cache, import/export, cloud synchronization, model training, model-weight
change or dictionary journal. App backup remains disabled. Stored phrases are
personal local data; they are not encryption-at-rest claims or credentials.

The activity re-reads the snapshot after ASR and routes an exact enabled match to an
already registered tool. Unavailable registry targets fail before execution. The
existing lifecycle/worker release sequence and ephemeral display/speech result
handling are retained. The route event adds only `source=personal_command` or
`source=built_in`; the timeline shows “ביטוי אישי זוהה” without copying an alias value.
Existing local ASR transcript journals/preferences still contain what the user said,
which can include a spoken personal phrase. Notification payload exclusions remain.

The editor is unexported, checks current lock state before every mutation, hides its
content and dismisses dialogs when paused. Unsaved edits are discarded on background
navigation; saved entries remain. Orientation changes retain the editor in place.
Opening this separate screen releases any foreground prepared voice workers through
the existing lifecycle; prepare again on return if needed. It does not automatically
start recording, run a tool or activate background listening.

## Verification and installation

Local JVM tests cover CRUD and toggling, whole-phrase boundaries, duplicate detection,
shared negation/punctuation handling, size/tool/schema validation, JSON round-trip,
store recreation/no-cache behavior, corrupt loads and write failures. Router tests
cover alias-to-registered-tool dispatch without private reads during selection,
execution-time locked result, built-in fallback and disabled/deleted aliases. Timeline
tests verify source display without copying private/unknown fields. Existing tests
for the matcher, tool, URL speech, workers and lifecycle remain in the suite.

Android Java compilation and **106 JVM tests** passed locally, with zero failures,
errors or skipped tests. No configured local Android emulator is available. APK
assembly and package checks run through exact-head GitHub CI. UI layout, native
storage durability and real acoustic acceptance remain device checks.

Candidate identity is `0.23-personal-commands`, versionCode 21, same application ID.
No APK has been installed for this slice; the last verified installed baseline is
Voice 0.22/code20. Installing requires explicit scope, exact-head CI artifact and a
matching existing signer; bridge remote install also needs the owner's local opt-in.
Do not uninstall or clear data to work around a signer mismatch.

After an approved update, verify empty state, add/edit/toggle/delete/reopen, duplicate
validation, rotation/background/lock, RTL at large font sizes, and both example voice
phrases. Test permission revoked, device locked, no notification, ordinary conversation,
cancellation and repeated turns. Do not upload personal phrases, notifications or raw
journals. CI success does not establish UI/acoustic/battery acceptance.

Rollback: preserve private dictionary data and return to the prior baseline only
with a compatible signed build; Android downgrade constraints still apply. Voice
0.22 ignores the new private dictionary file. No model/companion migration is needed.
