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

public class LiveMicTranscriber {
    private static final String TAG = "LiveMicTranscriber";
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int CHUNK_MS = 8000;
    private static final int CHUNK_BYTES = (SAMPLE_RATE * 2 * CHUNK_MS) / 1000;
    private static final double MIN_RMS = 28.0;
    private static final double TARGET_RMS = 1800.0;
    private static final double MAX_GAIN = 24.0;
    private static final int QUIET_CHUNKS_BEFORE_FORCED_ASR = 2;

    private final Context context;
    private final ScamAnalyzerAI analyzer;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Listener listener;
    private int quietChunks = 0;

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
        AudioRecord recorder = null;
        try {
            recorder = createBestRecorder();
            if (recorder == null) {
                throw new IllegalStateException("Could not open microphone for live transcription.");
            }

            recorder.startRecording();
            notifyStatus("Live cloud transcription is listening through the microphone.");

            byte[] readBuffer = new byte[Math.max(4096, AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT))];
            ByteArrayOutputStream chunk = new ByteArrayOutputStream(CHUNK_BYTES);

            while (running.get()) {
                int read = recorder.read(readBuffer, 0, readBuffer.length);
                if (read <= 0) continue;

                chunk.write(readBuffer, 0, read);
                if (chunk.size() >= CHUNK_BYTES) {
                    byte[] pcm = chunk.toByteArray();
                    chunk.reset();
                    transcribeChunkIfAudible(pcm);
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
        }
    }

    private AudioRecord createBestRecorder() {
        int[] sources = getCandidateSources();
        AudioRecord best = null;
        double bestRms = -1.0;
        int bestSource = -1;

        for (int source : sources) {
            AudioRecord candidate = createRecorder(source);
            if (candidate == null) continue;

            double probeRms = probeRecorder(candidate);
            Log.i(TAG, "Audio source " + source + " probe RMS=" + Math.round(probeRms));
            if (probeRms > bestRms) {
                if (best != null) best.release();
                best = candidate;
                bestRms = probeRms;
                bestSource = source;
            } else {
                candidate.release();
            }
        }

        if (best != null) {
            notifyStatus("Live cloud transcription selected mic source " + bestSource
                    + " (level " + Math.round(bestRms) + ").");
        }
        return best;
    }

    private int[] getCandidateSources() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return new int[]{
                    MediaRecorder.AudioSource.UNPROCESSED,
                    MediaRecorder.AudioSource.VOICE_CALL,
                    MediaRecorder.AudioSource.VOICE_DOWNLINK,
                    MediaRecorder.AudioSource.VOICE_UPLINK,
                    MediaRecorder.AudioSource.MIC,
                    MediaRecorder.AudioSource.CAMCORDER,
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION
            };
        }
        return new int[]{
                MediaRecorder.AudioSource.VOICE_CALL,
                MediaRecorder.AudioSource.VOICE_DOWNLINK,
                MediaRecorder.AudioSource.VOICE_UPLINK,
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.CAMCORDER,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.VOICE_COMMUNICATION
        };
    }

    private AudioRecord createRecorder(int source) {
        int minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        if (minBuffer <= 0) return null;

        int bufferSize = Math.max(minBuffer * 4, CHUNK_BYTES / 2);
        try {
            AudioRecord recorder = new AudioRecord(source, SAMPLE_RATE, CHANNEL_CONFIG,
                    AUDIO_FORMAT, bufferSize);
            if (recorder.getState() == AudioRecord.STATE_INITIALIZED) {
                return recorder;
            }
            recorder.release();
        } catch (Exception e) {
            Log.w(TAG, "AudioRecord source failed: " + source, e);
        }
        return null;
    }

    private double probeRecorder(AudioRecord recorder) {
        byte[] buffer = new byte[Math.max(4096, AudioRecord.getMinBufferSize(
                SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT))];
        ByteArrayOutputStream sample = new ByteArrayOutputStream(buffer.length * 3);

        try {
            recorder.startRecording();
            long deadline = System.currentTimeMillis() + 700L;
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

    private void transcribeChunkIfAudible(byte[] pcm) {
        double level = rms(pcm);
        if (level < MIN_RMS) {
            quietChunks++;
            if (Math.round(level) == 0 && quietChunks >= QUIET_CHUNKS_BEFORE_FORCED_ASR) {
                notifyDeadAudioInput();
            }
            if (quietChunks < QUIET_CHUNKS_BEFORE_FORCED_ASR) {
                notifyStatus("Mic level is very low (" + Math.round(level)
                        + "). Trying another chunk before cloud ASR.");
                return;
            }
            notifyStatus("Mic level is low (" + Math.round(level)
                    + "), but trying cloud ASR anyway.");
        } else {
            quietChunks = 0;
        }

        try {
            byte[] boosted = normalizeForAsr(pcm, level);
            double boostedLevel = rms(boosted);
            notifyStatus("Sending audio to cloud ASR (level " + Math.round(level)
                    + " -> " + Math.round(boostedLevel) + ").");

            String transcript = analyzer.transcribePcm16kMono(boosted);
            if (transcript != null && !transcript.trim().isEmpty()) {
                notifyTranscript(transcript.trim());
            }
        } catch (Exception e) {
            Log.w(TAG, "Cloud live chunk failed", e);
            String message = e.getMessage() != null ? e.getMessage() : "unknown ASR error";
            if (message.toLowerCase().contains("did not hear speech")
                    || message.toLowerCase().contains("empty transcript")) {
                notifyStatus("Still listening for clear caller speech...");
            } else {
                notifyStatus("Cloud ASR is still listening (" + message + ").");
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

    private void notifyTranscript(String text) {
        if (listener != null) listener.onTranscript(text);
    }

    private void notifyStatus(String status) {
        if (listener != null) listener.onStatus(status);
    }

    private void notifyError(String error) {
        if (listener != null) listener.onError(error);
    }

    private void notifyDeadAudioInput() {
        if (listener != null) listener.onDeadAudioInput();
    }
}
