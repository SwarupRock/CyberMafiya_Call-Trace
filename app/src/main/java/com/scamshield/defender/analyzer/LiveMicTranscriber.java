package com.scamshield.defender.analyzer;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ═══════════════════════════════════════════════════════════════════
 * LiveMicTranscriber — Speakerphone Mic Capture for Live ASR
 * ═══════════════════════════════════════════════════════════════════
 *
 * Consumer Android builds usually block direct call downlink capture.
 * This class therefore treats the active call like any other loud audio:
 * put the call on speakerphone, capture it with the microphone, transcribe
 * the mic stream, then feed that transcript into scam analysis.
 */
public class LiveMicTranscriber {
    private static final String TAG = "LiveMicTranscriber";
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int CHUNK_MS = 3000;       // 3s chunks
    private static final int CHUNK_BYTES = (SAMPLE_RATE * 2 * CHUNK_MS) / 1000;
    private static final double MIN_RMS = 4.0;       // Even lower threshold for telephony audio
    private static final double TARGET_RMS = 1800.0;
    private static final double MAX_GAIN = 60.0;     // Very aggressive amplification for quiet telephony audio
    private static final int QUIET_CHUNKS_BEFORE_REPROBE = 4;  // Reprobe after 4 quiet chunks (~12s)
    private static final int QUIET_CHUNKS_BEFORE_DEAD = 8;     // Give up after 8 quiet chunks (~24s)
    private static final int MAX_REPROBE_ATTEMPTS = 5;          // More reprobe attempts
    private static final long STARTUP_DELAY_MS = 2000L;         // Longer: wait for telephony audio to start
    private static final long PROBE_DURATION_MS = 1500L;        // Longer probe to catch intermittent audio

    private final Context context;
    private final ScamAnalyzerAI analyzer;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger reprobeCount = new AtomicInteger(0);
    private Listener listener;
    private int quietChunks = 0;
    private int totalChunksProcessed = 0;
    private int successfulTranscripts = 0;
    private int activeSourceId = -1;
    private String lastStatus = "";
    private long lastStatusAt = 0L;

    public interface Listener {
        void onTranscript(String text);
        void onStatus(String status);
        void onError(String error);
        void onDeadAudioInput();
    }

    public LiveMicTranscriber(Context context, ScamAnalyzerAI analyzer) {
        this.context = context.getApplicationContext();
        this.analyzer = analyzer;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) return;

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            running.set(false);
            notifyError("Microphone permission is required for live cloud transcription.");
            return;
        }

        if (!analyzer.hasAsrKey()) {
            running.set(false);
            notifyStatus("Android live recognizer active. Add the NVIDIA ASR key for stronger in-call transcription.");
            return;
        }

        executor.execute(this::recordLoop);
    }

    public void stop() {
        running.set(false);
    }

    public boolean isRunning() {
        return running.get();
    }

    private void recordLoop() {
        // Wait for speaker routing and call audio to settle.
        try {
            Log.i(TAG, "Waiting " + STARTUP_DELAY_MS + "ms for speakerphone audio to start...");
            notifyStatus("Listening through microphone for caller audio...");
            Thread.sleep(STARTUP_DELAY_MS);
        } catch (InterruptedException e) {
            if (!running.get()) return;
        }

        AudioRecord recorder = null;
        try {
            recorder = createBestRecorder();
            if (recorder == null) {
                notifyError("Could not open any audio source for call capture.");
                notifyDeadAudioInput();
                return;
            }

            recorder.startRecording();
            notifyStatus("Live transcription is listening through the phone microphone.");
            totalChunksProcessed = 0;
            successfulTranscripts = 0;
            quietChunks = 0;

            byte[] readBuffer = new byte[Math.max(4096, AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT))];
            ByteArrayOutputStream chunk = new ByteArrayOutputStream(CHUNK_BYTES);

            while (running.get()) {
                int read = recorder.read(readBuffer, 0, readBuffer.length);
                if (read <= 0) {
                    Log.w(TAG, "AudioRecord.read returned " + read);
                    continue;
                }

                chunk.write(readBuffer, 0, read);
                if (chunk.size() >= CHUNK_BYTES) {
                    byte[] pcm = chunk.toByteArray();
                    chunk.reset();
                    totalChunksProcessed++;
                    double level = rms(pcm);
                    Log.i(TAG, "Chunk #" + totalChunksProcessed
                            + " src=" + activeSourceId
                            + " size=" + pcm.length + " RMS=" + Math.round(level)
                            + " quietStreak=" + quietChunks);
                    processChunk(pcm, level);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Live mic transcription stopped", e);
            notifyError("Live cloud transcription failed: " + e.getMessage());
        } finally {
            if (recorder != null) {
                try {
                    recorder.stop();
                } catch (Exception ignored) {
                }
                recorder.release();
            }
            running.set(false);
            Log.i(TAG, "Recording stopped. Source=" + activeSourceId
                    + " Chunks=" + totalChunksProcessed
                    + " Transcripts=" + successfulTranscripts);
        }
    }

    /**
     * Try microphone sources and pick the one with the highest RMS.
     * Direct telephony sources are intentionally skipped because most
     * devices return hard zero audio for third-party apps.
     */
    private AudioRecord createBestRecorder() {
        int[] sources = getMicrophoneSources();
        AudioRecord best = null;
        double bestRms = -1.0;
        int bestSource = -1;

        notifyStatus("Checking microphone audio for speakerphone transcription...");

        for (int source : sources) {
            String sourceName = sourceIdToName(source);
            Log.i(TAG, "Probing source: " + sourceName + " (" + source + ")");
            AudioRecord candidate = createRecorder(source);
            if (candidate == null) {
                Log.i(TAG, "  → " + sourceName + ": FAILED to create");
                continue;
            }

            double probeRms = probeRecorder(candidate);
            Log.i(TAG, "  → " + sourceName + ": RMS=" + Math.round(probeRms));

            if (probeRms > bestRms) {
                if (best != null) best.release();
                best = candidate;
                bestRms = probeRms;
                bestSource = source;
            } else {
                candidate.release();
            }

            // If we found a good source, stop searching
            if (probeRms > MIN_RMS * 2) {
                Log.i(TAG, "Found good source: " + sourceName + " RMS=" + Math.round(probeRms));
                break;
            }
        }

        // If probing happened during silence, still start with MIC and wait
        // for the caller to speak.
        if (best == null || bestRms <= 0) {
            Log.w(TAG, "Microphone probe was quiet. Forcing MIC and waiting for speech...");
            notifyStatus("Microphone is open. Waiting for the caller to speak...");

            int[] forceSources = new int[]{
                    MediaRecorder.AudioSource.MIC,
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    MediaRecorder.AudioSource.CAMCORDER
            };
            for (int source : forceSources) {
                AudioRecord forced = createRecorder(source);
                if (forced != null) {
                    if (best != null) best.release();
                    best = forced;
                    bestSource = source;
                    Log.i(TAG, "Force-selected source: " + sourceIdToName(source));
                    break;
                }
            }
        }

        if (best != null) {
            activeSourceId = bestSource;
            String msg = "Using audio source: " + sourceIdToName(bestSource)
                    + " (level " + Math.round(bestRms) + ")";
            Log.i(TAG, msg);
            notifyStatus(msg);
        }
        return best;
    }

    /**
     * Microphone sources for speakerphone capture.
     */
    private int[] getMicrophoneSources() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return new int[]{
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,   // 6
                    MediaRecorder.AudioSource.MIC,                 // 1
                    MediaRecorder.AudioSource.UNPROCESSED,          // 9
                    MediaRecorder.AudioSource.CAMCORDER            // 5
            };
        }
        return new int[]{
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.CAMCORDER
        };
    }

    private AudioRecord createRecorder(int source) {
        int minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        if (minBuffer <= 0) return null;

        int bufferSize = Math.max(minBuffer * 4, CHUNK_BYTES);
        try {
            AudioRecord recorder = new AudioRecord(source, SAMPLE_RATE, CHANNEL_CONFIG,
                    AUDIO_FORMAT, bufferSize);
            if (recorder.getState() == AudioRecord.STATE_INITIALIZED) {
                return recorder;
            }
            Log.w(TAG, "AudioRecord source " + source + " not initialized");
            recorder.release();
        } catch (SecurityException e) {
            Log.w(TAG, "SecurityException for source " + source + ": " + e.getMessage());
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Invalid source " + source + ": " + e.getMessage());
        } catch (Exception e) {
            Log.w(TAG, "AudioRecord source failed: " + source, e);
        }
        return null;
    }

    /**
     * Probe a recorder for PROBE_DURATION_MS to check if we get audio.
     * Extended to 1.5s for telephony sources which may have delayed start.
     */
    private double probeRecorder(AudioRecord recorder) {
        byte[] buffer = new byte[Math.max(4096, AudioRecord.getMinBufferSize(
                SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT))];
        ByteArrayOutputStream sample = new ByteArrayOutputStream(buffer.length * 5);

        try {
            recorder.startRecording();
            long deadline = System.currentTimeMillis() + PROBE_DURATION_MS;
            while (System.currentTimeMillis() < deadline) {
                int read = recorder.read(buffer, 0, buffer.length);
                if (read > 0) sample.write(buffer, 0, read);
            }
            recorder.stop();
        } catch (Exception e) {
            Log.w(TAG, "Audio source probe failed", e);
            try {
                recorder.stop();
            } catch (Exception ignored) {
            }
        }

        return rms(sample.toByteArray());
    }

    private void processChunk(byte[] pcm, double level) {
        if (level < MIN_RMS) {
            quietChunks++;

            if (quietChunks >= QUIET_CHUNKS_BEFORE_DEAD) {
                Log.w(TAG, "Dead audio after " + quietChunks + " chunks");
                notifyStatus("Still no microphone audio from the speaker. Check Android microphone access and call speaker output.");
                notifyDeadAudioInput();
                return;
            }

            // Still try ASR for non-zero audio
            if (level > 0) {
                notifyStatusThrottled("Audio very quiet (level " + Math.round(level)
                        + "). Sending to cloud ASR...");
                sendToAsr(pcm, level);
            } else {
                notifyStatusThrottled("Waiting for caller speech through the microphone...");
            }
            return;
        }

        // Got audio!
        quietChunks = 0;
        sendToAsr(pcm, level);
    }

    private void sendToAsr(byte[] pcm, double level) {
        try {
            byte[] boosted = normalizeForAsr(pcm, level);
            double boostedLevel = rms(boosted);
            Log.i(TAG, "Sending to NVIDIA ASR: raw=" + Math.round(level)
                    + " boosted=" + Math.round(boostedLevel)
                    + " bytes=" + boosted.length);
            notifyStatusThrottled("Sending to cloud ASR (level " + Math.round(level)
                    + " → " + Math.round(boostedLevel) + ")...");

            String transcript = analyzer.transcribePcm16kMono(boosted);
            if (transcript != null && !transcript.trim().isEmpty()) {
                successfulTranscripts++;
                Log.i(TAG, "✅ Transcript received: " + transcript.trim());
                notifyTranscript(transcript.trim());
                notifyStatus("Transcribing call audio...");
            } else {
                Log.d(TAG, "ASR returned empty/null transcript");
            }
        } catch (Exception e) {
            Log.w(TAG, "Cloud ASR chunk failed", e);
            String message = e.getMessage() != null ? e.getMessage() : "unknown ASR error";
            if (message.toLowerCase().contains("did not hear speech")
                    || message.toLowerCase().contains("empty transcript")
                    || message.toLowerCase().contains("did not hear clear speech")) {
                notifyStatus("Listening for caller speech...");
            } else {
                Log.e(TAG, "ASR error: " + message);
                notifyStatus("Cloud ASR error: " + message);
            }
        }
    }

    private byte[] normalizeForAsr(byte[] pcm, double level) {
        if (level <= 0.0) return pcm;

        double gain = Math.min(MAX_GAIN, TARGET_RMS / Math.max(1.0, level));
        if (gain <= 1.05) return pcm;

        byte[] out = new byte[pcm.length];
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            int sample = (pcm[i] & 0xff) | ((pcm[i + 1] & 0xff) << 8);
            if (sample > 32767) sample -= 65536;

            int boosted = (int) Math.round(sample * gain);
            boosted = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, boosted));

            out[i] = (byte) (boosted & 0xff);
            out[i + 1] = (byte) ((boosted >> 8) & 0xff);
        }
        return out;
    }

    private double rms(byte[] pcm) {
        long sumSquares = 0L;
        int samples = pcm.length / 2;
        if (samples == 0) return 0.0;

        for (int i = 0; i + 1 < pcm.length; i += 2) {
            int sample = (pcm[i] & 0xff) | ((pcm[i + 1] & 0xff) << 8);
            if (sample > 32767) sample -= 65536;
            sumSquares += (long) sample * sample;
        }
        return Math.sqrt(sumSquares / (double) samples);
    }

    public boolean canReprobe() {
        return reprobeCount.get() < MAX_REPROBE_ATTEMPTS;
    }

    public void reprobe() {
        if (!canReprobe()) return;
        reprobeCount.incrementAndGet();
        Log.i(TAG, "Re-probing audio sources (attempt " + reprobeCount.get() + "/" + MAX_REPROBE_ATTEMPTS + ")");
        stop();
        try {
            Thread.sleep(800);
        } catch (InterruptedException ignored) {}
        running.set(false);
        quietChunks = 0;
        start();
    }

    private String sourceIdToName(int source) {
        switch (source) {
            case MediaRecorder.AudioSource.MIC: return "MIC";
            case MediaRecorder.AudioSource.VOICE_UPLINK: return "VOICE_UPLINK";
            case MediaRecorder.AudioSource.VOICE_DOWNLINK: return "VOICE_DOWNLINK";
            case MediaRecorder.AudioSource.VOICE_CALL: return "VOICE_CALL";
            case MediaRecorder.AudioSource.CAMCORDER: return "CAMCORDER";
            case MediaRecorder.AudioSource.VOICE_RECOGNITION: return "VOICE_RECOGNITION";
            case MediaRecorder.AudioSource.VOICE_COMMUNICATION: return "VOICE_COMMUNICATION";
            case 9: return "UNPROCESSED"; // MediaRecorder.AudioSource.UNPROCESSED = 9
            default: return "SOURCE_" + source;
        }
    }

    private void notifyTranscript(String text) {
        if (listener != null) listener.onTranscript(text);
    }

    private void notifyStatus(String status) {
        if (listener == null || status == null || status.trim().isEmpty()) return;

        long now = System.currentTimeMillis();
        if (status.equals(lastStatus) && now - lastStatusAt < 8_000L) return;

        lastStatus = status;
        lastStatusAt = now;
        listener.onStatus(status);
    }

    private void notifyStatusThrottled(String status) {
        if (listener == null || status == null || status.trim().isEmpty()) return;

        long now = System.currentTimeMillis();
        if (now - lastStatusAt < 8_000L) return;

        lastStatus = status;
        lastStatusAt = now;
        listener.onStatus(status);
    }

    private void notifyError(String error) {
        if (listener != null) listener.onError(error);
    }

    private void notifyDeadAudioInput() {
        if (listener != null) listener.onDeadAudioInput();
    }
}
