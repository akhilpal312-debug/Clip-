# Architecture

```
app                         Compose host, navigation, foreground processing service, recovery worker
├── feature-home            dashboard
├── feature-video-import    SAF picker, library, queueing
├── feature-analysis        queue + job detail, pipeline state, detected moments, transcript
├── feature-clips           best/balanced lists, preview, export, gallery history
├── feature-editor          trim, aspect, caption style/editor, re-render
├── feature-live-monitor    live sources, validation, monitor control
├── feature-model-manager   install/verify/pause/delete, download log
└── feature-settings        profile, ranking, output, privacy, storage
core-data                   AppContainer (manual DI), repositories, AnalysisPipeline, LiveMonitor
├── core-database           Room: videos, jobs, checkpoints, transcripts, candidates, clips,
│                           models, exports, live streams
├── core-storage            app file layout + MediaStore exporter
├── core-network            resumable SHA-256 downloader, YouTube Data API client
├── core-audio              MediaCodec -> 16 kHz mono PCM, VAD, band-energy features
├── core-video              probing, adaptive frame sampling, scenes, EGL/MediaCodec renderer
├── core-ai                 whisper.cpp, llama.cpp, LiteRT vision, Silero VAD, registry, ranking
├── core-models             pure-Kotlin domain types (no Android deps)
└── core-common             theme, components, formatting, device profiling
```

Dependency direction is one-way: features → core-data → core-* → core-models. No DI framework is
used; `AppContainer` builds the graph once and is handed to feature ViewModels.

## Processing pipeline

`AnalysisPipeline.runJob(jobId)` is the only place that orchestrates a job:

1. **PROBE** — `VideoProber` (MediaMetadataRetriever + MediaExtractor track scan).
2. **AUDIO_EXTRACT** — `AudioExtractor` decodes the first audio track with MediaCodec, mixes to
   mono and streams a resampled 16 kHz WAV to the job directory. `audio.wav` is reused on resume.
3. **TRANSCRIBE** — the WAV is read in 30 s windows (`WavReader`), silent windows are skipped by
   RMS, and each window runs whisper.cpp with token timestamps. Segments are offset to global
   time, near-duplicates at window seams are dropped, and rows are persisted every ten windows.
4. **AUDIO_FEATURES** — `AudioAnalyzer` computes per-100 ms RMS, band-limited flux and speech
   probability, plus loudness peaks and repeated-burst candidates.
5. **VISION** — `FrameSampler` picks an interval from duration and profile (bounded frame counts),
   then either `VisionAnalyzer` (LiteRT labels + platform face detection) or the OpenGL-free
   histogram fallback produces `FrameAnalysis` rows and scene cuts.
6. **SCORE** — `ClipDetector` groups sentences, expands to natural boundaries, scores nine weighted
   signals, merges overlaps, drops near-duplicates, and splits into Best/Balanced quality.
7. **RENDER/CAPTION** — for each candidate: `CaptionBuilder` builds cues with word timings,
   `ClipTextGenerator` (local GGUF or extractive) writes title/description/hashtags, and
   `ClipRenderer` composes the reframed video with burned captions and re-encoded AAC.
8. **EXPORT** — `ClipRepository.export` writes the MP4 into MediaStore under
   `Movies/AI Clip Creator/<folder>` and records the URI.

Each stage writes a checkpoint; on restart `recoverInterruptedJobs()` re-queues RUNNING jobs and the
pipeline skips stages whose artifacts already exist (WAV file, transcript rows, candidates, clips).

## Rendering

`ClipRenderer` decodes video onto a `SurfaceTexture`, draws it into the encoder's input surface
with a cover-fit MVP matrix, composites the caption bitmap as a second textured quad, and encodes
H.264. Audio is decoded to PCM (mono 44.1 kHz), optionally peak-normalised, and encoded to AAC.
Timestamps are rebased to the clip start and forced monotonic. The muxer adds both tracks before
`start()`; samples produced before both formats are known are buffered.

## Queue and background work

- `ProcessingService` is a foreground service (type `dataSync`) that drains the queue via
  `AppContainer.processNextQueuedJob()` and mirrors progress into the notification.
- `RecoveryWorker` is a periodic WorkManager job that re-queues interrupted jobs and restarts the
  service when work is waiting, surviving reboots.
- All queue state lives in Room, so nothing depends on the service being alive.

## Live monitoring

`LiveMonitor` records a copy-based MP4 window (`LiveRecorder`, MediaExtractor → MediaMuxer, no
re-encode) from an HLS/progressive HTTP URL, registers it as a video, creates a job tagged with the
live session, and runs the normal pipeline. Clips from live sessions go to `Live Streams`.

## Model lifecycle

`ModelManager` owns the registry, downloads, verification and one runtime instance per category.
Models are loaded lazily after install (self-test performs a real inference) and can be unloaded or
deleted individually. `ModelStore`/`ModelRepository` separate the AI module from Room.

## Why these choices

- MediaCodec/OpenGL instead of shipping FFmpeg: no huge prebuilt binary, and the platform codecs
  use hardware acceleration. The trade-off is that rendering is paced at roughly real time.
- Chunked WAV + windowed whisper instead of whole-file decoding: a 5-hour recording would need
  ~1.1 GB as float PCM; the pipeline stays at a few megabytes.
- A crash-free fallback for every optional model: without the LLM, captions come from the
  transcript; without LiteRT, scene/motion analysis still runs.
