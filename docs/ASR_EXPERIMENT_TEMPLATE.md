# ASR experiment record template

Use this template for a new experiment or a later device result. Add its ID and concise result to [ASR_EXPERIMENT_HISTORY.md](ASR_EXPERIMENT_HISTORY.md). Keep private audio, transcripts, model binaries and SDK/signing material outside Git. Record non-sensitive artifact identifiers and hashes instead. Hebrew explanations are preferred for the user-facing history.

## ASR-XX — Title

| Field | Value |
|---|---|
| Code date / device test date and timezone | Not measured / TBD |
| Status | Planned / build passed / device pending / measured pass / measured fail |
| Hypothesis | What specific bottleneck or quality issue is being addressed? |
| Baseline experiment ID and full commit | TBD |
| Candidate full commit / PR / APK version / APK SHA-256 | TBD |
| Changed factors | Exact model, graph, search or runtime changes |
| Controlled factors | Same input/model/thread/window settings, or explicitly different |
| Acceptance criteria defined before testing | Throughput, coverage, quality and stability |
| Rollback full commit / required model set | TBD |

### Reproduction identity

| Item | Value |
|---|---|
| Phone / SoC / Android build fingerprint | TBD |
| whisper.cpp commit and patches | TBD |
| Model repository / revision / file SHA-256 | List CPU decoder, TPU encoder and TPU cross separately |
| Export and AOT versions | Python / Torch / Transformers / litert-torch / LiteRT / SDK; retain private pip freeze |
| Native build flags / JDK / Gradle / NDK / CMake | TBD |
| Backend by component | Frontend / encoder / K-V preparation / token decoder |
| Beam size / best_of / temperature / fallback / CPU threads | TBD |
| Window / overlap / queue capacity / token and time budgets | TBD |
| Source audio ID / SHA-256 / duration / sample rate | Private artifact reference, no content |
| Input delivery | Internal PCM replay / same recording via microphone / text reread / synthetic |
| Loading included in timing? | Yes / no; report separately |
| Starting temperature / charging / other workloads | Observed values; do not guess |
| Runs / warm-up / execution order | Count and order; identify any excluded run with reason |

### Evidence and results

| Measurement | Baseline | Candidate | Evidence / limitation |
|---|---|---|---|
| CI build and test run | TBD | TBD | CI link + tested commit |
| Host conversion parity | Not measured | Not measured | Input fixtures and error metrics |
| On-device Q5/TPU cache parity | Not measured | Not measured | Per-layer relative L2, cosine, max abs |
| Model loading ms | Not measured | Not measured | Distinct from inference |
| Audio captured / unique audio processed / unprocessed s | Not measured | Not measured | Include gaps and final tail |
| Full-window mel / encoder / cross / decode / compute ms | Not measured | Not measured | Mean, and spread if multiple runs/windows |
| Native sampling vs decode timing | Not measured | Not measured | Timer boundaries |
| Cumulative RTF / shared-prefix RTF | Not measured | Not measured | Same audio interval, overlap counted once |
| Backlog trend / peak / queue overflow | Not measured | Not measured | Journal offsets and queue depth |
| Memory PSS / thermal status | Not measured | Not measured | Does not prove clock/thermal causality |
| Transcript accuracy | Not measured | Not measured | Human review or reference-based WER/CER |
| Numbers / names / negations / English / boundary text | Not measured | Not measured | Do not commit personal examples |
| Crash / freeze / timeout / reboot | Not observed or not measured | Not observed or not measured | Exact APK/backend; retain private logs |

### Interpretation

- What the evidence establishes:
- What remains unproven:
- Comparable interval and confounders (different audio, tails, load, temperature):
- Decision: keep / reject / pending, with reason:
- Next smallest experiment:
- Private artifact IDs and SHA-256 for source audio, reference, journal and diagnostic logs:

### Follow-up results

Append dated observations here instead of erasing the first result. Distinguish new device evidence from corrections to an earlier interpretation. Update the central history with the same experiment ID; mark roadmap completion only after the required work is merged to main.
