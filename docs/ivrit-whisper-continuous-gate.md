# Continuous Hebrew ASR throughput gate (foreground, experimental)

See the [central ASR explanation and experiment history](ASR_EXPERIMENT_HISTORY.md) for later results, evidence limits and rollback points. This guide describes its specific experimental stage.

Goal: prove sustained local transcription before connecting a local LLM or supporting hours of background recording. This branch builds on the unmerged TPU cross-attention integration, not on completed production streaming support.

## Evidence and target

The measured 5.36-second example completed in 8.59 seconds including model loading. Encoder took 2534 ms, cross preparation plus transfers 272 ms, cache injection and CPU decoding 2887 ms. This demonstrates the accelerated path but does not establish real-time throughput or long-session stability.

Two final product paths need separate acceptance checks:

- Live assistant: stable phrase text within a target of 1–3 seconds after a phrase, followed by local LLM work. Provisional text must not automatically trigger tasks.
- Conversation archive: hours of recording, incremental durable transcripts, timestamps and recovery after interruption. Speaker identification and reliable background microphone operation remain future work.

The new gate uses fixed 12-second audio windows with a 1-second overlap. First text arrives after the first window plus inference. It is a throughput diagnostic, not the low-latency assistant implementation. Whisper still encodes its fixed 30-second padded input; increasing the diagnostic window amortizes that cost. Model weights and runtime buffers remain loaded for one session.

## Behavior

After step 6 passes the existing Q5/TPU cache comparison, step 8 opens a foreground-screen test lasting at most 10 minutes. Models load once before recording begins. Capture and inference use separate threads in a private `:stream` process. The queue holds two windows; overflow stops recording and reports incomplete coverage rather than silently dropping audio or allowing unlimited growth. Normal Stop drains the queue and processes the final partial window (at least 0.5 seconds). A native token limit fails explicitly rather than returning an apparently complete truncated chunk.

Each completed segment is appended to app-private `files/asr-sessions/*.jsonl`, flushed and synced before appearing in the UI. Records include sample offsets, raw provisional chunk text, phase times, backlog, queue depth, process PSS and Android thermal status. The terminal record marks incomplete coverage. Abrupt process death can leave a journal without a terminal record; previously synced segment lines remain available, but unprocessed audio is not saved. There is no automatic retention cleanup in this diagnostic.

The displayed merged text removes exact overlaps of 2–12 words. This is a provisional heuristic: repeated phrases can be ambiguous and punctuation changes may leave duplicates. Raw segment text is preserved for inspection. Sample timestamps describe audio windows, not word timings or speaker turns.

Keeping the screen open is required. Leaving the screen aborts the trial and kills the private worker; this is not background recording support. Models are read from the existing external app model directory; no model recompilation or new model push is needed. No transcript or recording is uploaded. The native inference watchdog is 90 seconds; it can terminate a stuck trial, preserving completed journal records only.

## Device acceptance

1. Build/install the branch; verify step 6 if its saved gate is not already present.
2. Start step 8 and speak for one minute; Stop and inspect transcript and reported unprocessed audio.
3. Run for ten minutes with natural speech and pauses. Inspect each segment's compute time, cumulative RTF, backlog, memory and thermal status. RTF is compute time divided by unique audio duration, excluding overlap and initial loading.
4. Require RTF below 1 with useful headroom, backlog that does not grow continually, no queue overflow, bounded memory and acceptable transcription quality including window boundaries. A short passing sentence is insufficient.
5. Inspect heat-related slowdown over the trial. Test silence, fast speech, repetitions, stop mid-window and screen interruption.

Only after this passes should the next experiment target shorter phrase/VAD boundaries and measure phrase-end-to-stable-text latency. If sustained throughput fails, profile encoder and token decode separately and evaluate a model designed for streaming with demonstrated Hebrew quality. Background foreground-service recording, durable raw-audio recovery, hour-long tests and co-running the local LLM are separate gates.

To inspect saved journals from a debug APK with a connected device:

```sh
adb shell run-as life.mosaic.tensorwhisper ls files/asr-sessions
adb exec-out run-as life.mosaic.tensorwhisper cat files/asr-sessions/SESSION_NAME.jsonl > session.jsonl
```

## Verification

CI builds the Android APK/native bridge and runs standalone Java tests for exact sample coverage, overlap, partial-tail flushing, PCM conversion and provisional text reconciliation. Device throughput, thermal behavior and transcription quality require the Pixel test; CI does not claim these pass.

## Decoder throughput A/B trial (v0.7)

The supplied device-test analysis reports 111 s captured, 100 s transcribed, 11 s unprocessed and cumulative RTF 1.283. Mean encoder time was approximately 2.65 s and decoder time approximately 11.3 s per chunk. This is user-session evidence, not a new measurement on this branch. The source journal/audio is not committed.

Step 8 now offers Beam 2 (default experimental candidate), Beam 5 (previous baseline) and Greedy (beam 1). Every session fixes its selection before recording. CPU threads remain 2; the TPU artifacts, 12 s windows, 1 s overlap, queue capacity, token guard and cancellation budgets stay the same. The existing short-utterance and parity steps retain their old settings. Beam 2 reduces the search width, but speedup and Hebrew accuracy remain unverified until tested on Pixel. No automatic switch or fallback hides which decoder ran.

Start records now use diagnostic version 2 and include beam size, CPU threads and APK version. Segments also include the selected beam and whisper.cpp native timing breakdown (decode/batch/prompt vs sampling). The previous version-1 journal remains readable by the comparison script.

### Repeat the same source

1. Update the existing APK in place; do not uninstall or replace model files. If signature mismatch occurs, build using the same machine/signing key as the installed APK. The prior parity gate and private journals should be preserved by an in-place update.
2. Open step 8, choose Beam 2, and wait for the loaded/recording indication before starting speech.
3. Replay the exact same source recording, at the same volume and placement, where available. Reading the same text again is an indicative comparison because pacing and window boundaries change. Stop after the source finishes and wait for queue/tail processing to complete.
4. Save the new JSONL. Compare the shared first 100 s with the previous incomplete run, then assess coverage of the complete text. Do not compare only total average decoding if one run includes a short Stop tail; inspect the full-window metrics and shared chunk offsets.
5. Require zero unprocessed audio, sustained RTF below 1 (target 0.75–0.85), stable backlog, and acceptable Hebrew/English terminology, names and numbers. Review raw segment text for omissions separately from boundary duplicates. Lower RTF alone does not pass the quality gate.
6. If accuracy regresses, use Beam 5 on the same source for control; Greedy is an optional separate experiment. Do not merge or mark streaming complete on the basis of this APK build.

After retrieving both journals using the adb commands above:

```sh
python3 scripts/compare_asr_sessions.py baseline.jsonl candidate.jsonl
```

This prints coverage, full-window decode/compute means, cumulative RTF, peak backlog, memory and thermal status, plus percentage reductions. Missing terminal records are marked interrupted with unknown uncaptured tail rather than treated as successful runs. It does not compute WER or claim accuracy without a reference transcript. All journal processing remains local.

## Separate durable archive trial

Step 9 in v0.8 records PCM locally first, then transcribes after Stop and can resume missing chunks. This is a separate offline/archive trial, not a passing realtime gate. See [archive workflow](ivrit-whisper-audio-archive.md) and ASR-18 in the [experiment history](ASR_EXPERIMENT_HISTORY.md). The audio persistence and terminal-before-cleanup ordering apply to step 9; step 8 retains its prior baseline behavior.
