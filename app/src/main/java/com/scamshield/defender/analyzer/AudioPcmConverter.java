package com.scamshield.defender.analyzer;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

class AudioPcmConverter {
    private static final int TARGET_SAMPLE_RATE = 16000;
    private static final int TIMEOUT_US = 10000;

    static PcmAudio toMono16k(Context context, byte[] audioData, String mimeType) throws Exception {
        PcmAudio wav = tryReadWav(audioData);
        if (wav != null) {
            return wav.toMono16k();
        }
        return decodeWithAndroid(context, audioData, mimeType).toMono16k();
    }

    private static PcmAudio tryReadWav(byte[] data) {
        if (data.length < 44 || data[0] != 'R' || data[1] != 'I' || data[2] != 'F' || data[3] != 'F'
                || data[8] != 'W' || data[9] != 'A' || data[10] != 'V' || data[11] != 'E') {
            return null;
        }

        int offset = 12;
        int channels = 1;
        int sampleRate = TARGET_SAMPLE_RATE;
        int bitsPerSample = 16;
        int dataOffset = -1;
        int dataSize = 0;

        while (offset + 8 <= data.length) {
            String chunkId = new String(data, offset, 4);
            int chunkSize = readLeInt(data, offset + 4);
            int chunkData = offset + 8;
            if ("fmt ".equals(chunkId) && chunkData + 16 <= data.length) {
                int audioFormat = readLeShort(data, chunkData);
                channels = readLeShort(data, chunkData + 2);
                sampleRate = readLeInt(data, chunkData + 4);
                bitsPerSample = readLeShort(data, chunkData + 14);
                if (audioFormat != 1 || bitsPerSample != 16) {
                    return null;
                }
            } else if ("data".equals(chunkId)) {
                dataOffset = chunkData;
                dataSize = Math.min(chunkSize, data.length - chunkData);
                break;
            }
            offset = chunkData + chunkSize + (chunkSize % 2);
        }

        if (dataOffset < 0 || dataSize <= 0) return null;
        byte[] pcm = new byte[dataSize];
        System.arraycopy(data, dataOffset, pcm, 0, dataSize);
        return new PcmAudio(pcm, sampleRate, channels);
    }

    private static PcmAudio decodeWithAndroid(Context context, byte[] audioData, String mimeType) throws Exception {
        File temp = File.createTempFile("call-trace-audio", extensionFor(mimeType), context.getCacheDir());
        try (FileOutputStream fos = new FileOutputStream(temp)) {
            fos.write(audioData);
        }

        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(temp.getAbsolutePath());
            int trackIndex = selectAudioTrack(extractor);
            if (trackIndex < 0) {
                throw new IllegalStateException("No audio track found in the selected file.");
            }

            extractor.selectTrack(trackIndex);
            MediaFormat inputFormat = extractor.getTrackFormat(trackIndex);
            String codecMime = inputFormat.getString(MediaFormat.KEY_MIME);
            if (codecMime == null) {
                throw new IllegalStateException("Unknown audio format.");
            }

            int sampleRate = inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    : TARGET_SAMPLE_RATE;
            int channels = inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    : 1;

            codec = MediaCodec.createDecoderByType(codecMime);
            codec.configure(inputFormat, null, null, 0);
            codec.start();

            ByteArrayOutputStream pcm = new ByteArrayOutputStream();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;

            while (!outputDone) {
                if (!inputDone) {
                    int inputIndex = codec.dequeueInputBuffer(TIMEOUT_US);
                    if (inputIndex >= 0) {
                        ByteBuffer inputBuffer = codec.getInputBuffer(inputIndex);
                        if (inputBuffer == null) continue;
                        inputBuffer.clear();
                        int sampleSize = extractor.readSampleData(inputBuffer, 0);
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            long presentationTimeUs = extractor.getSampleTime();
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, presentationTimeUs, 0);
                            extractor.advance();
                        }
                    }
                }

                int outputIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US);
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat outputFormat = codec.getOutputFormat();
                    if (outputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    }
                    if (outputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    }
                } else if (outputIndex >= 0) {
                    ByteBuffer outputBuffer = codec.getOutputBuffer(outputIndex);
                    if (outputBuffer != null && info.size > 0) {
                        byte[] chunk = new byte[info.size];
                        outputBuffer.position(info.offset);
                        outputBuffer.limit(info.offset + info.size);
                        outputBuffer.get(chunk);
                        pcm.write(chunk, 0, chunk.length);
                    }
                    outputDone = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    codec.releaseOutputBuffer(outputIndex, false);
                }
            }

            return new PcmAudio(pcm.toByteArray(), sampleRate, channels);
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (Exception ignored) {
                }
                codec.release();
            }
            extractor.release();
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
        }
    }

    private static int selectAudioTrack(MediaExtractor extractor) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                return i;
            }
        }
        return -1;
    }

    private static String extensionFor(String mimeType) {
        if (mimeType == null) return ".audio";
        if (mimeType.contains("mpeg")) return ".mp3";
        if (mimeType.contains("mp4") || mimeType.contains("aac")) return ".m4a";
        if (mimeType.contains("ogg")) return ".ogg";
        if (mimeType.contains("wav")) return ".wav";
        return ".audio";
    }

    private static int readLeShort(byte[] data, int offset) {
        return (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8);
    }

    private static int readLeInt(byte[] data, int offset) {
        return (data[offset] & 0xff)
                | ((data[offset + 1] & 0xff) << 8)
                | ((data[offset + 2] & 0xff) << 16)
                | ((data[offset + 3] & 0xff) << 24);
    }

    static class PcmAudio {
        final byte[] pcm16le;
        final int sampleRate;
        final int channels;

        PcmAudio(byte[] pcm16le, int sampleRate, int channels) {
            this.pcm16le = pcm16le;
            this.sampleRate = sampleRate;
            this.channels = Math.max(1, channels);
        }

        PcmAudio toMono16k() {
            short[] source = bytesToShorts(pcm16le);
            int frameCount = source.length / channels;
            short[] mono = new short[frameCount];
            for (int frame = 0; frame < frameCount; frame++) {
                int sum = 0;
                for (int channel = 0; channel < channels; channel++) {
                    sum += source[(frame * channels) + channel];
                }
                mono[frame] = (short) (sum / channels);
            }

            if (sampleRate == TARGET_SAMPLE_RATE) {
                return new PcmAudio(shortsToBytes(mono), TARGET_SAMPLE_RATE, 1);
            }

            int outFrames = Math.max(1, Math.round(mono.length * (TARGET_SAMPLE_RATE / (float) sampleRate)));
            short[] resampled = new short[outFrames];
            for (int i = 0; i < outFrames; i++) {
                float srcPos = i * (sampleRate / (float) TARGET_SAMPLE_RATE);
                int base = Math.min((int) srcPos, mono.length - 1);
                int next = Math.min(base + 1, mono.length - 1);
                float fraction = srcPos - base;
                resampled[i] = (short) (mono[base] + ((mono[next] - mono[base]) * fraction));
            }
            return new PcmAudio(shortsToBytes(resampled), TARGET_SAMPLE_RATE, 1);
        }

        private static short[] bytesToShorts(byte[] bytes) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            short[] shorts = new short[bytes.length / 2];
            for (int i = 0; i < shorts.length; i++) {
                shorts[i] = buffer.getShort();
            }
            return shorts;
        }

        private static byte[] shortsToBytes(short[] shorts) {
            ByteBuffer buffer = ByteBuffer.allocate(shorts.length * 2).order(ByteOrder.LITTLE_ENDIAN);
            for (short value : shorts) {
                buffer.putShort(value);
            }
            return buffer.array();
        }
    }
}
