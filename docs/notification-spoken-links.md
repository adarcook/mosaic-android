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
