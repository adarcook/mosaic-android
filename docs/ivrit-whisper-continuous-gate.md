# Continuous Hebrew ASR throughput gate (foreground, experimental)

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
