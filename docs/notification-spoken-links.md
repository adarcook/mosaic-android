# ASR-37 / VOICE-15 — WhatsApp URLs as spoken placeholders

2026-10-18, Asia/Jerusalem. Baseline 0.21 implementation `737a5e903beee3f9645dad18be0d6609678c6ce4`, evidence `14bccca`. User confirmed the WhatsApp routing fix, but full URLs were spelled aloud. Existing general VoiceText normalization deliberately spells Latin letters and digits; a long private notification took 89,272 ms in TTS with 56.703 s output. Those metrics include actual content and are not a short-command benchmark. No private URL/message/sender is published.

## Candidate and boundaries

Voice 0.22 / code20. Before general normalization, WhatsApp-only spoken output replaces URL-like spans with “קישור מצורף”; whitespace-adjacent links become “קישורים מצורפים”. Handles HTTP(S), www, bare ASCII domains/paths/queries, short links, Unicode scheme URLs, Hebrew prefixes and trailing punctuation. Email addresses, numeric versions and ordinary surrounding text are preserved. Hebrew immediately following comma/semicolon/etc. is treated as message prose. Recognition is syntactic and does not resolve domains, validate destinations or follow links; unusual ambiguous strings may be classified as URL-like. Full original bounded result remains ephemeral in the conversation UI. Source snapshot is unchanged; displayed text and speech have separate rendering.

Native notification/keyguard/signature/content bounds and message exclusion from TTS journals remain unchanged. Sanitized speech still contains private message text and must not be logged. No link fetching/opening or external message is sent. ASR, models/graphs, G2P/TTS/seed, backend placement, cached clear cues, stage timing/lifecycle and sequential workers unchanged; CPU wake/TTS two threads, Gemma requests Tensor G5 NPU. This formats input; it is not inference acceleration or new ASR quality evidence.

## Acceptance / reproduction

Pure Java regressions: query/tracking URL must not reach speech; surrounding Hebrew, punctuation, email and numbers preserved; scheme/www/bare/Unicode/shortened links replaced; adjacent links summarized; original display/snapshot retain exact URL; tool spoken text then normalizes without spelling URL letters; native unavailable messages unchanged. Local controller tests/build and exact-head CI/artifact required before same-signer data-preserving update. No uninstall/data clear or signature bypass. Retain prior APK/model/journal directories.

Check installation identity and explicit activation. Existing 0.21 native route/OK/TTS privacy behavior was already observed; optional real-device reread may verify speech/UI rendering, but text tests do not prove acoustic quality or sustained stability. No test message is sent by the agent. Inspect only route/tool status/terminal flags; do not export URL or message. Do not compare reread notification durations as controlled speedup unless identical source/settings are available.

## Status and rollback

Local build/tests, exact candidate SHA/CI/APK and device preparation will be appended. Prior route fix remains accepted for one native trial. Rollback `14bccca` / implementation `737a5e903beee3f9645dad18be0d6609678c6ce4`, rebuilt with non-decreasing version code and existing signer; preserve data. Draft #49, no merge or roadmap completion.

Local controller build and all 80 tests passed, including eight new link rendering/integration tests. These use public synthetic example domains, not the user notification.

A further boundary case covers many tiny bare domains: if long placeholders would expand speech beyond 1,100 characters, individual placeholders shorten to a single word, preserving surrounding message prose and the existing 1,200-character TTS input bound. Final controller build and 81 tests passed (nine new URL cases).


## Installation evidence — 2026-10-18, Asia/Jerusalem

Final code `7173a0bad5f426014c2965fc302dd767f1e4f251` passed exact-head LLM workflow `39127374830` and Zipformer `39127374794` before install. Controller CI artifact `12137135258`, ZIP digest `5b2da94aae2915fa5d5b367a072e6319b12019dec6ebfb63f42acffb157ca439`. Remaining workflows still in progress at that observation. Local 0.22/code20 APK SHA-256 `86c1854a3baf9baf49abd7733379eaf18781e88f60f904c26204afb069718af5`; prior 0.21 backup `62167b3632a6fb3234823995825d27ccc8686de85f2b98217e1747ea995fa0be`. Existing/candidate signer verified equal to `ef154770847924693d77aaceda155a8ef66751f30d2219c72e967ea0a6a61e35` before update. Data-preserving install succeeded and package version confirmed by the installer; companion/models/data/journals retained. Phone locked at completion; user asked to unlock for explicit reactivation. No uninstall, data clearing, signature/permission/keyguard bypass or merge. Physical speech/UI rendering in 0.22 remains unmeasured; pure rendering/integration tests and prior native tool success are separate evidence.


## Explicit reactivation — 2026-10-18, 15:35 Asia/Jerusalem

After user unlock, installed 0.22 / unlocked interactive state verified. Explicit activation completed in 16,035 ms; cached clear cues verified/reused in 38 ms. Listener reported listening at `1792326934131` and subsequent numeric health processed 4,900 ms PCM over 5,007 ms wall time while interactive. Own-process crash buffer was empty at inspection. No new WhatsApp read was requested or private content collected in this activation. Native URL acoustic/UI readout in 0.22 is not newly measured by preparation; rendering behavior is covered by the nine pure URL/integration regressions and prior tool-path runtime evidence. No controlled latency/quality or sustained-reliability claim.

All five workflows on final code `7173a0bad5f426014c2965fc302dd767f1e4f251` completed successfully: LLM `39127374830`, Zipformer `39127374794`, voice-loop `39127374761`, Whisper hybrid `39127374751`, Android Build `39127374823`. Standby left explicitly enabled with its normal stop notification. PR remains Draft/unmerged, data/models/signature and keyguard protections preserved.
