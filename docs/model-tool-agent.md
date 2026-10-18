# ASR-38 / VOICE-16 — Model-selected local tools

2026-10-18, Asia/Jerusalem. Current candidate Voice 0.24.2 / versionCode 24;
initial device candidate was 0.24/code22. This experiment replaces
the production voice path's deterministic tool selection with native LiteRT-LM
function calls. It is stacked on draft #51 and remains unmerged.

## Hypothesis, baseline and acceptance gate

| Field | Value |
|---|---|
| Baseline / rollback source | `2373982d7bffaf3ea29cfe127ea42d42e1978076`, installed Voice 0.23/code21 |
| Baseline installed APK SHA-256 | `7bc3775a0b3fae1e90e4114e45eaab4b372c2666eeab794e6e8a214e3fa3f19b` |
| Current main inspected | `613b71ebd3d94dcb7e84f70af058a818739fb3f9`, approved UI baseline via #49 |
| Related open work | #50 routing, #51 dictionary; #44/#48 already ancestors of main; bridge #47 separate |
| Hypothesis | The existing Gemma 4 pack can choose a structured notification tool and write a truthful final answer from its typed result, without a keyword router choosing for it |
| Changed factors | Voice system instruction, advertised tool schema, native manual function-calling loop, correlated Binder result, private-output guards |
| Controlled factors | Same weights, LiteRT-LM 0.18.0, NPU-only backend, dispatch, sampler (20/.9/.2/seed0), 512 output-token budget, thinking disabled, ASR/TTS models and inference settings |
| Candidate source/APK/hash | PR implementation commit and exact-head CI artifact will identify it; not invented at authoring |
| Initial status | Host tests/Android compilation passed; CI and physical model acceptance pending |

Acceptance defined before running the model: the fixed debug probe must choose no
tool for greeting, negation and explanation; one notification call for an explicit
WhatsApp read request and the owner's exact saved alias. Each selected call receives
a synthetic `NO_MESSAGE` result and must finish with a non-empty answer, without a
second tool call. Human review must separately check that the answer reflects the
failure truthfully. No latency improvement or broad intent-accuracy claim follows
from five fixtures.

The physical live gate remains distinct: a new spoken request must travel through
ASR → model → validated native tool → model → TTS, with permission denial, lock,
no-message, cancellation and repeated-turn cases. A successful debug fixture does
not establish real-notification readout or microphone/TTS acceptance.

## Actual decision and execution path

Every voice transcript now goes to `LlmService`. `VoiceActivity` no longer calls
`VoiceToolRouter.route` to decide between WhatsApp and Gemma. A matching saved
personal phrase becomes an allowlisted meaning hint and, from 0.24.2, expands to
the full user request for that saved action. Its raw text remains in the original
ASR display/journal and is not copied into system instructions. Expansion never
executes the tool; the model must emit the structured call, and the original raw
request still governs the execution veto.

`NativeAgentModel` registers the app-owned zero-argument
`read_latest_whatsapp_notification` schema and sets `automaticToolCalling=false`.
It consumes `Message.getToolCalls()`; text that resembles JSON is never parsed as
an executable action. The legacy matcher/helpers remain for regression coverage,
but the voice path does not invoke their positive-routing logic. A conservative
negation/unsupported-action check remains an execution veto, not tool selection.

`ManualToolAgent` allows at most one structured call. Unknown names, arguments,
multiple calls, blank/oversized final replies and any additional call after a result
fail closed. The LLM worker asks the owning activity through a correlated Binder
exchange; the activity validates again and resolves an already registered app tool.
The existing Android adapter checks notification access and keyguard at execution.

The worker is idle while waiting (20 s maximum) for the native result, retaining
the same conversation/KV state. `Content.ToolResponse` sends status/content back in
the TOOL role. The model generates the final answer. There is no automatic SDK
execution callback, arbitrary tool URL/package, shell, network fallback, send/reply/
delete capability or text-command execution. The total generation deadline is 150 s
across both rounds; the parent's existing 240 s stage budget still includes loading.
Cancellation disposes the worker; stale/duplicate/late IPC replies cannot resume it.

Backend split: ASR remains the existing selected CPU/TPU path; Gemma runs on Tensor
G5 NPU; the notification adapter is ordinary Android/CPU work; BlueTTS runs on CPU.
No ASR/LLM/TTS heavy worker is loaded in parallel with another heavy worker. The
LLM remains resident during the small native read and exits through the existing
observed-death gate before TTS loads. No claim of model/backend acceleration.

## Privacy and limitations

Voice-agent LLM journals contain metadata only. They omit user text, tool arguments,
tool bodies and generated answers. Existing local ASR transcript/journal behavior
still records the user's spoken input. Parent diagnostics exclude `agent_*` payloads;
tool-derived answers are not saved in preferences or TTS `normalized_text` fields.
The UI marks “המודל בחר כלי” only after validated model selection.

Private final answers are checked again before display/TTS. Private TTS checks
current permission and keyguard before synthesis/playback and during audio writes/
drain. Background presentation is cleared; screen-off cancels an active private
tool turn. Error messages are content-free. Spoken URLs still use placeholders.
Native privacy behavior requires device checks; these guards do not constitute a
proof that generated summaries always quote a message faithfully. Tool content is
untrusted data in its own role, with system instructions against following embedded
instructions and a hard one-call limit preventing another tool execution.

The first catalog entry remains read-only WhatsApp, with no arguments. This slice
establishes the model-driven loop; broader schemas, action confirmations and multiple
tool steps are follow-up work. Archive transcript summarization remains tool-free.

## Evidence, reproduction and rollback

Official API/source references (pinned runtime inspected against the downloaded AAR):
[API overview](https://developers.google.com/edge/litert-lm/api_overview#function-calling-tool-use),
[v0.18.0 Conversation](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.18.0/kotlin/java/com/google/ai/edge/litertlm/Conversation.kt),
[v0.18.0 Message](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.18.0/kotlin/java/com/google/ai/edge/litertlm/Message.kt),
[v0.18.0 Tool](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.18.0/kotlin/java/com/google/ai/edge/litertlm/Tool.kt).

Host tests exercise a fake model port to verify orchestration, denial/cancellation,
ToolResponse roles/status, one-call bounds, schema-only SDK providers, personal hints,
correlation and diagnostic privacy. They are **not** model-accuracy measurements.
The debug-only `AgentProbeInstrumentation` uses five fixed public synthetic inputs,
the real pinned Gemma/NPU runtime and synthetic native results. It accepts no custom
prompt, never opens the microphone, never reads notifications and never invokes TTS.
Its answers may be inspected locally because all fixture inputs/results are synthetic.

Reproduce on a matching-signer, exact-head CI debug APK with existing models:

```text
adb shell am instrument -w life.mosaic.tensorllm/life.mosaic.tensorllm.AgentProbeInstrumentation
```

The probe verifies full model/dispatch hashes, creates a fresh conversation per case
and retains one loaded engine for the fixture set. Do not run it concurrently with
active speech workers. ADB/device authorization is required; the probe is absent
from release builds. Result/code, original CI ZIP, locally signed APK and any pulled
installed APK hashes must be recorded separately. No private model/SDK/key/audio/
transcript is committed.

Identity: Gemma model/dispatch file hashes and sizes remain in `ModelSpec`; BlueTTS
revision remains in `TtsPack`; ASR companion unchanged. JDK22/Gradle8.9 local, pinned
JDK21 hosted controller workflow. No parity, real audio, WER/CER, sustained latency,
thermal cause, battery or multi-turn stability measurement is claimed. Probe timings
are not an A/B benchmark. Android fingerprint/charging/temperature and exact runtime
results remain unmeasured until the device gate and should not be guessed.

Rollback source is the 0.23 baseline above. Preserve app data, models and dictionary;
use compatible signing and respect Android version-code downgrade rules. Keep this
PR draft and leave main/roadmap completion unchanged.

## First physical probe — 0.24, 2026-10-18

Implementation `6ee634bd37657e1ea3a01414e3e605afa0f02760` passed 123 local tests
and all three triggered CI workflows: controller `39135401078`, voice-loop
`39135401075`, Android Build `39135401102`. Controller artifact `12139622845`,
ZIP SHA-256 `9417f3708bda384c2183f3c61ac0e07f8ce06eb520ac113e86a0c39217a06d3e`.
CI APK SHA-256 `081b8f3392f0d6010f7888ad690a85c0c15a3387f05f230c0cf34055cc27aa9f`.
The CI signature differed; the existing local development signer matched the
installed 0.23. Re-signing preserved all non-signature ZIP entry hashes. A normal
data-preserving update installed 0.24, and the pulled-back APK/hash/signer matched:
`c54cb6aec3e1f2ab2ee4cf033b9ad8bfdefd12a7927a0e59cbfc814cf3735111`.
The prior 0.23 APK was backed up (hash in baseline table), and the private dictionary
file hash stayed identical. No bridge opt-in/permission/model change or data clear.

Real Gemma/NPU with synthetic native results passed **4/5 fixtures**: greeting,
explicit WhatsApp read, negation and explanation. Explicit read produced one native
structured call; the returned `NO_MESSAGE` led to a truthful no-available-notification/
no-history-access final answer. The personal alias fixture produced zero calls and
asked for clarification: the English function-name-only preference was insufficient.
This is a failed alias acceptance gate, not a production pass. No actual notification,
microphone or TTS was used. Android identity and raw synthetic probe are retained
locally; no private device data is published.

0.24.1 changes only the alias interpretation context to an explicit app-owned Hebrew
meaning (“the user asks to read the latest WhatsApp notification”), alongside the
allowlisted function name. It still requires the model to emit a native call; there
is no alias execution shortcut. The same five fixtures will be rerun with identical
weights/runtime/sampler/schema and synthetic result. New CI/artifact/install/probe
identity and results are pending at authoring. No speed comparison is made.

## Second physical probe and input-expansion candidate

0.24.1 implementation `8d4ec41cb7cb54208ce0121784befd76b292e642` again passed
123 host tests and controller CI (`39135932534`, artifact `12139313359`). ZIP
SHA-256 `4e0143660809edcd73a1c98e0555541847d995afcf4119cccaf35e9db34240b4`;
CI APK `580fd467a10095da7082582b7861a550fc61c35434a45c075e56453ec261444f`;
locally signed/pulled installed APK
`1618e47344194cea7e9e7bdc07f4bd8420ab273b52a3d9160442ad3c7d762d02`.
Normal matching-signer update preserved dictionary data. Broader CI was still
running at installation; no source outside the alias context/version changed.

The same real-model fixture set again passed **4/5**, with the same explicit-call
success and three correct no-call cases. The alias offered to read WhatsApp but
still asked what to read. A system-only Hebrew meaning hint did not pass the alias
gate. No real notifications/audio/TTS were used; this failed outcome is retained.

0.24.2 expands the exact owner-defined alias into the full canonical user request
before model inference, while retaining the meaning hint. The expansion is
allowlisted app code, not arbitrary text from the dictionary; original raw input
remains the execution-veto input. There is still no deterministic execution or
keyword fallback: a model answer without a native call performs no read. This
implements the owner's stored meaning rather than leaving an ambiguous utterance
as the sole USER message. Same five-case probe, model/schema/sampler/results;
current build/install/probe evidence pending at authoring.

## Third physical probe — 0.24.2 passes the fixed gate

Implementation/source `edc37a7bbb8d3e554b4c9827e11f94c6e56090b0` passed **124
local tests**, zero failures/errors/skips, and all three triggered CI workflows:
[controller 39136270379](https://github.com/adarcook/mosaic-android/actions/runs/39136270379),
[voice-loop 39136270566](https://github.com/adarcook/mosaic-android/actions/runs/39136270566),
[Android Build 39136270376](https://github.com/adarcook/mosaic-android/actions/runs/39136270376).
Controller artifact `12139213978`; ZIP SHA-256
`998f10d0993fdf93654f9c4f857fba6bc7820410c169bc10f45e40b9fef3c6e8`;
original CI APK `72dfdd518df40009fbcb1d2efd6b7afca096ce3ef5431434f543d9e29f3faba1`.
The same local matching-signer procedure preserved all non-signature APK contents,
updated in place, and verified the pulled installed 0.24.2/code24 APK:
`7ac8100aa6951aaeea6123d8969f535556ce603a82ebd9434d1316b5802dcbc9`.
The prior 0.24.1 APK was backed up (hash in the preceding record). The private
dictionary hash stayed identical; no data clear, model/permission/bridge change.

All **5/5 fixed cases passed** on real Gemma/NPU: greeting 0 calls, explicit read
1 call, saved alias 1 call, negated request 0, explanation 0. Both call cases
received synthetic `NO_MESSAGE` through the native TOOL message and produced
truthful final answers stating no available notification and no history access,
reviewed locally. There were no actual notification reads, microphone recordings
or TTS runs. Probe output SHA-256:
`efbb0423b5075b76db0c314a67430d73cac0ff34a02744d964b4f1a6a6cea73d`.
Earlier probe output hashes are
`523dbaa7aa10259839037804dfec12b7631b4ed56e0185439d5b1636db1bd06a`
(0.24) and `8304edb41d16d35fb0142a3423880a18bdfc55946e31f62a31c1f9aee9eab379`
(0.24.1). Raw output and APKs remain in ignored local QA folders.

Device: Pixel 10 Pro / Tensor G5, fingerprint
`google/blazer/blazer:17/CP3A.261005.005/16271449:user/release-keys`.
At the last probe battery reported no AC/USB/wireless charging and 27.1 °C battery
temperature; this is not SoC temperature or evidence of thermal causality.
No latency measurement/A-B comparison or broad language accuracy claim is made.

Decision: keep the model-selected loop as the current installed development
candidate, with the fixed synthetic gate passed. Live speech, real Binder-to-
notification readout, lock/revocation/cancellation during private response and TTS,
additional phrasings and sustained turns remain acceptance gates. Main/roadmap
unchanged, draft #52 unmerged. This evidence-only follow-up does not change the
installed application sources or require another installation.
