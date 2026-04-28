package com.scamshield.defender.analyzer;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.annotations.SerializedName;

import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class ScamAnalyzerAI {

    private static final String TAG = "ScamAnalyzerAI";
    private static final float LOCAL_ALERT_THRESHOLD = 0.40f;
    private static final float SCAM_CONFIRMED_THRESHOLD = 0.70f;
    private static final float CLOUD_TRIGGER_THRESHOLD = 0.30f;
    private static final String PREFS_NAME = "scam_shield_prefs";
    private static final String PREF_NVIDIA_LLM_API_KEY = "nvidia_llm_api_key";
    private static final String PREF_NVIDIA_ASR_API_KEY = "nvidia_asr_api_key";
    private static final String NVIDIA_MODEL = "meta/llama-3.1-8b-instruct";
    private static final String NVIDIA_ENDPOINT = "https://integrate.api.nvidia.com/v1/chat/completions";
    private static final String NVIDIA_RIVA_RECOGNIZE_URL =
            "https://grpc.nvcf.nvidia.com/nvidia.riva.asr.RivaSpeechRecognition/Recognize";
    private static final String NVIDIA_PARAKEET_FUNCTION_ID = "1598d209-5e27-4d3c-8079-4751568b1081";
    private static final MediaType GRPC_MEDIA_TYPE = MediaType.get("application/grpc");

    private static final Map<String, String[]> SCAM_KEYWORDS = new HashMap<>();

    static {
        SCAM_KEYWORDS.put("banking", new String[]{
                "otp", "cvv", "card number", "bank account", "upi pin",
                "credit card", "debit card", "account number", "ifsc",
                "transfer money", "net banking", "mobile banking", "upi",
                "paytm", "google pay", "phonepe", "share otp", "tell me otp",
                "give me otp", "send otp", "read otp", "otp number",
                "bank details", "account details", "card details",
                "transaction failed", "refund", "cashback credited",
                "calling from sbi", "calling from hdfc", "calling from icici",
                "calling from axis", "calling from kotak", "calling from bank",
                "your account has been", "your card has been", "your loan",
                "emi due", "overdue payment", "pending amount",
                "insurance claim", "policy expired", "premium due"
        });
        SCAM_KEYWORDS.put("kyc", new String[]{
                "kyc", "verification", "verify your", "account blocked",
                "account suspended", "update kyc", "pan card", "aadhaar",
                "aadhar", "pan number", "kyc expired", "complete kyc",
                "link aadhaar", "link aadhar", "verify identity", "identity proof"
        });
        SCAM_KEYWORDS.put("urgency", new String[]{
                "immediately", "right now", "urgent", "last chance",
                "within 24 hours", "your account will be", "act now",
                "do it now", "deadline", "final warning", "before midnight",
                "expiring today", "closing soon", "limited time",
                "do it fast", "hurry up", "time is running out"
        });
        SCAM_KEYWORDS.put("authority", new String[]{
                "police", "cbi", "income tax", "government", "rbi",
                "reserve bank", "court order", "legal action", "arrest warrant",
                "digital arrest", "cyber crime", "customs", "narcotics", "fir filed",
                "enforcement directorate", "sebi", "trai", "department of telecom"
        });
        SCAM_KEYWORDS.put("lottery", new String[]{
                "lottery", "prize", "won", "winner", "congratulations",
                "lucky draw", "claim your", "reward", "crore", "lakh won",
                "gift card", "free gift", "selected for", "chosen for"
        });
        SCAM_KEYWORDS.put("tech_support", new String[]{
                "anydesk", "teamviewer", "remote access", "install app",
                "download app", "screen share", "quick support", "give access",
                "share screen", "control your phone", "access your device"
        });
        SCAM_KEYWORDS.put("threats", new String[]{
                "disconnect", "electricity", "gas connection", "sim card",
                "deactivate", "parcel", "courier", "drugs found",
                "money laundering", "your number is involved", "complaint registered",
                "case filed", "warrant issued", "jail", "prison"
        });
        SCAM_KEYWORDS.put("call_bomber", new String[]{
                "pick up the phone", "answer my call", "why not answering",
                "i will keep calling", "harassment", "threatening"
        });
    }

    private final Context appContext;
    private final ExecutorService cloudExecutor = Executors.newSingleThreadExecutor();
    private final Gson gson = new Gson();
    private final OkHttpClient rivaClient = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)
            .build();
    private final List<String> detectedKeywords = new ArrayList<>();

    private float currentScore = 0f;
    private String threatCategory = "none";
    private int analysisCount = 0;
    private boolean cloudAnalysisDone = false;
    private AnalysisResult latestResult;
    private ScamDetectionListener scamListener;

    public ScamAnalyzerAI(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public interface ScamDetectionListener {
        void onAnalysisUpdate(AnalysisResult result);
        void onScamAlert(AnalysisResult result);
        void onScamConfirmed(AnalysisResult result);
    }

    public static class AnalysisResult {
        public float confidence;
        public boolean isScam;
        public String threatType;
        public String reasoning;
        public List<String> keywordsFound;
        public boolean fromCloud;

        public AnalysisResult() {
            keywordsFound = new ArrayList<>();
            threatType = "none";
            reasoning = "";
        }

        public String getThreatLevel() {
            if (confidence >= 0.70f) return "HIGH RISK";
            if (confidence >= 0.40f) return "MEDIUM RISK";
            if (confidence >= 0.10f) return "LOW RISK";
            return "SAFE";
        }
    }

    private static class CloudResponse {
        @SerializedName("is_scam") boolean isScam;
        @SerializedName("confidence") float confidence;
        @SerializedName("threat_type") String threatType;
        @SerializedName("reasoning") String reasoning;
        @SerializedName("keywords_found") List<String> keywordsFound;
    }

    public void setScamDetectionListener(ScamDetectionListener listener) {
        this.scamListener = listener;
    }

    public void analyzeChunk(String textChunk, String fullTranscript) {
        analysisCount++;
        Log.d(TAG, "Analyzing chunk #" + analysisCount + ": " + textChunk);

        float keywordScore = runKeywordAnalysis(textChunk);
        float patternScore = runPatternAnalysis(fullTranscript);
        float combinedLocal = (keywordScore * 0.6f) + (patternScore * 0.4f);
        currentScore = Math.max(currentScore, combinedLocal);

        AnalysisResult result = new AnalysisResult();
        result.confidence = currentScore;
        result.isScam = currentScore >= LOCAL_ALERT_THRESHOLD;
        result.threatType = threatCategory;
        result.keywordsFound = new ArrayList<>(detectedKeywords);
        result.fromCloud = false;
        result.reasoning = detectedKeywords.isEmpty()
                ? "No scam indicators detected"
                : "Detected " + detectedKeywords.size() + " scam indicators in " + threatCategory;

        latestResult = result;
        notifyListeners(result);

        if (currentScore >= CLOUD_TRIGGER_THRESHOLD && !cloudAnalysisDone
                && fullTranscript.length() > 20) {
            triggerCloudAnalysis(fullTranscript);
        }
    }

    public void reset() {
        currentScore = 0f;
        detectedKeywords.clear();
        threatCategory = "none";
        analysisCount = 0;
        cloudAnalysisDone = false;
        latestResult = null;
    }

    public AnalysisResult getLatestResult() {
        return latestResult;
    }

    public float getCurrentScore() {
        return currentScore;
    }

    private void notifyListeners(AnalysisResult result) {
        if (scamListener == null) return;

        scamListener.onAnalysisUpdate(result);
        if (currentScore >= SCAM_CONFIRMED_THRESHOLD) {
            scamListener.onScamConfirmed(result);
        } else if (currentScore >= LOCAL_ALERT_THRESHOLD) {
            scamListener.onScamAlert(result);
        }
    }

    private float runKeywordAnalysis(String text) {
        String lower = text.toLowerCase();
        float score = 0f;
        float maxCategoryScore = 0f;

        for (Map.Entry<String, String[]> entry : SCAM_KEYWORDS.entrySet()) {
            String category = entry.getKey();
            float categoryScore = 0f;

            for (String keyword : entry.getValue()) {
                if (lower.contains(keyword) && !detectedKeywords.contains(keyword)) {
                    detectedKeywords.add(keyword);
                    categoryScore += getKeywordWeight(category);
                }
            }

            score += categoryScore;
            if (categoryScore > maxCategoryScore) {
                maxCategoryScore = categoryScore;
                threatCategory = category;
            }
        }

        return Math.min(score, 1.0f);
    }

    private float getKeywordWeight(String category) {
        switch (category) {
            case "banking":
                return 0.20f;
            case "authority":
            case "tech_support":
                return 0.18f;
            case "threats":
            case "kyc":
            case "lottery":
                return 0.15f;
            case "urgency":
                return 0.10f;
            default:
                return 0.10f;
        }
    }

    private float runPatternAnalysis(String fullTranscript) {
        String lower = fullTranscript.toLowerCase();
        float score = 0f;

        int urgencyCount = 0;
        String[] urgencyPhrases = {"immediately", "right now", "urgent", "hurry",
                "now or", "last chance", "time is running"};
        for (String phrase : urgencyPhrases) {
            if (lower.contains(phrase)) urgencyCount++;
        }
        if (urgencyCount >= 2) score += 0.25f;

        String[] credentialRequests = {
                "what is your", "tell me your", "share your",
                "give me your", "confirm your", "verify your"
        };
        for (String pattern : credentialRequests) {
            if (lower.contains(pattern)) {
                score += 0.15f;
                break;
            }
        }

        boolean hasFinancial = lower.contains("money") || lower.contains("transfer")
                || lower.contains("payment") || lower.contains("rupees")
                || lower.contains("amount");
        boolean hasPressure = lower.contains("now") || lower.contains("immediate")
                || lower.contains("urgent");
        if (hasFinancial && hasPressure) score += 0.20f;

        String[] impersonation = {"i am calling from", "this is from",
                "we are from", "calling from bank"};
        for (String pattern : impersonation) {
            if (lower.contains(pattern)) {
                score += 0.10f;
                break;
            }
        }

        int words = fullTranscript.trim().isEmpty()
                ? 0
                : fullTranscript.trim().split("\\s+").length;
        if (words > 0 && detectedKeywords.size() > 0) {
            float density = (float) detectedKeywords.size() / words;
            if (density > 0.1f) score += 0.15f;
        }

        return Math.min(score, 1.0f);
    }

    private void triggerCloudAnalysis(String transcript) {
        cloudAnalysisDone = true;
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String apiKey = prefs.getString(PREF_NVIDIA_LLM_API_KEY, "");
        if (apiKey == null || apiKey.trim().isEmpty()) {
            Log.w(TAG, "NVIDIA LLM API key missing. Local analysis remains active.");
            return;
        }

        cloudExecutor.execute(() -> {
            try {
                CloudResponse cloud = callNvidia(apiKey.trim(), transcript);
                applyCloudResult(cloud);
            } catch (Exception e) {
                Log.w(TAG, "NVIDIA analysis failed. Local analysis remains active: " + e.getMessage());
            }
        });
    }

    private CloudResponse callNvidia(String apiKey, String transcript) throws Exception {
        URL url = new URL(NVIDIA_ENDPOINT);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setDoOutput(true);

        String prompt = "You are Call Trace analyzing an English phone-call transcript in real time. " +
                "Return only JSON with keys: is_scam boolean, confidence number 0-1, " +
                "threat_type string, reasoning string, keywords_found string array. " +
                "Flag OTP, bank or KYC impersonation, urgent account freeze, remote access, legal threats, " +
                "refund traps, and credential requests. Transcript: " + transcript;

        JsonArray messages = new JsonArray();
        JsonObject systemMessage = new JsonObject();
        systemMessage.addProperty("role", "system");
        systemMessage.addProperty("content", "Return strict JSON only. No markdown.");
        messages.add(systemMessage);
        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        userMessage.addProperty("content", prompt);
        messages.add(userMessage);

        JsonObject body = new JsonObject();
        body.addProperty("model", NVIDIA_MODEL);
        body.add("messages", messages);
        body.addProperty("temperature", 0.1);
        body.addProperty("max_tokens", 512);

        try (OutputStream os = conn.getOutputStream()) {
            byte[] input = gson.toJson(body).getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

        int code = conn.getResponseCode();
        String response = readHttpResponse(conn, code);
        conn.disconnect();

        if (code < 200 || code >= 300) {
            throw new IllegalStateException("NVIDIA HTTP " + code + ": " + response);
        }

        String modelText = extractChatCompletionText(response);
        return gson.fromJson(stripJsonFence(modelText), CloudResponse.class);
    }

    private String readHttpResponse(HttpURLConnection conn, int code) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream(),
                StandardCharsets.UTF_8));
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            response.append(line);
        }
        reader.close();
        return response.toString();
    }

    private String extractChatCompletionText(String responseJson) {
        JsonObject root = JsonParser.parseString(responseJson).getAsJsonObject();
        JsonArray choices = root.getAsJsonArray("choices");
        if (choices == null || choices.size() == 0) {
            throw new IllegalStateException("NVIDIA returned no choices");
        }
        JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
        if (message == null || !message.has("content")) {
            throw new IllegalStateException("NVIDIA returned no message content");
        }
        return message.get("content").getAsString();
    }

    private String stripJsonFence(String text) {
        if (text == null) return "";
        String cleaned = text.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceFirst("^```(?:json)?", "").trim();
            if (cleaned.endsWith("```")) {
                cleaned = cleaned.substring(0, cleaned.length() - 3).trim();
            }
        }
        return cleaned;
    }

    private void applyCloudResult(CloudResponse cloud) {
        if (cloud == null) return;

        AnalysisResult result = buildCloudAnalysisResult(cloud, currentScore, threatCategory);
        currentScore = result.confidence;
        threatCategory = result.threatType;

        latestResult = result;
        notifyListeners(result);

        Log.i(TAG, "Cloud verdict: " + (cloud.isScam ? "SCAM" : "SAFE")
                + " (" + (cloud.confidence * 100) + "%)");
    }

    private AnalysisResult buildCloudAnalysisResult(CloudResponse cloud, float fallbackScore, String fallbackThreat) {
        AnalysisResult result = new AnalysisResult();
        result.confidence = Math.max(cloud.confidence, fallbackScore);
        result.isScam = cloud.isScam || result.confidence >= LOCAL_ALERT_THRESHOLD;
        result.threatType = cloud.threatType != null ? cloud.threatType : fallbackThreat;
        result.reasoning = cloud.reasoning != null ? cloud.reasoning : "NVIDIA AI analysis";
        result.keywordsFound = cloud.keywordsFound != null && !cloud.keywordsFound.isEmpty()
                ? new ArrayList<>(cloud.keywordsFound)
                : new ArrayList<>(detectedKeywords);
        result.fromCloud = true;
        return result;
    }

    // ═══════════════════════════════════════════════════════════════
    // AUDIO FILE ANALYSIS (User Upload)
    // ═══════════════════════════════════════════════════════════════

    public interface AudioAnalysisCallback {
        void onTranscriptReady(String transcript);
        void onAnalysisComplete(AnalysisResult result, String transcript);
        void onError(String error);
    }

    private static class AudioFileResponse {
        @SerializedName("transcript") String transcript;
        @SerializedName("is_scam") boolean isScam;
        @SerializedName("confidence") float confidence;
        @SerializedName("threat_type") String threatType;
        @SerializedName("risk_level") String riskLevel;
        @SerializedName("reasoning") String reasoning;
        @SerializedName("keywords_found") List<String> keywordsFound;
    }

    public void analyzeAudioFile(byte[] audioData, String mimeType, AudioAnalysisCallback callback) {
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String asrKey = prefs.getString(PREF_NVIDIA_ASR_API_KEY, "");
        String llmKey = prefs.getString(PREF_NVIDIA_LLM_API_KEY, "");
        if (asrKey == null || asrKey.trim().isEmpty()) {
            callback.onError("NVIDIA ASR/Riva API key is required for audio transcription.");
            return;
        }
        if (llmKey == null || llmKey.trim().isEmpty()) {
            callback.onError("NVIDIA LLM API key is required for scam analysis.");
            return;
        }

        cloudExecutor.execute(() -> {
            try {
                String transcript = transcribeAudioWithNvidia(asrKey.trim(), audioData, mimeType);
                if (transcript == null || transcript.trim().isEmpty()) {
                    throw new IllegalStateException("NVIDIA ASR returned an empty transcript.");
                }

                String cleanedTranscript = transcript.trim();
                callback.onTranscriptReady(cleanedTranscript);

                CloudResponse cloud = callNvidia(llmKey.trim(), cleanedTranscript);
                AnalysisResult result = buildCloudAnalysisResult(cloud, 0f, "none");
                callback.onAnalysisComplete(result, cleanedTranscript);
            } catch (Exception e) {
                Log.e(TAG, "NVIDIA audio analysis failed", e);
                callback.onError("NVIDIA audio analysis failed: " + e.getMessage());
            }
        });
    }

    private String transcribeAudioWithNvidia(String apiKey, byte[] audioData, String mimeType) throws Exception {
        AudioPcmConverter.PcmAudio pcm = AudioPcmConverter.toMono16k(appContext, audioData, mimeType);
        byte[] recognizeRequest = buildRecognizeRequest(pcm.pcm16le);
        byte[] grpcBody = frameGrpcMessage(recognizeRequest);

        Request request = new Request.Builder()
                .url(NVIDIA_RIVA_RECOGNIZE_URL)
                .header("Authorization", "Bearer " + apiKey)
                .header("function-id", NVIDIA_PARAKEET_FUNCTION_ID)
                .header("te", "trailers")
                .header("grpc-encoding", "identity")
                .header("grpc-accept-encoding", "identity")
                .post(RequestBody.create(grpcBody, GRPC_MEDIA_TYPE))
                .build();

        try (Response response = rivaClient.newCall(request).execute()) {
            byte[] body = response.body() != null ? response.body().bytes() : new byte[0];
            String grpcStatus = response.header("grpc-status");
            if (!response.isSuccessful()) {
                throw new IllegalStateException("NVIDIA ASR connection failed (" + response.code() + ").");
            }
            if (grpcStatus != null && !"0".equals(grpcStatus)) {
                String message = response.header("grpc-message", "Unknown NVIDIA ASR error");
                throw new IllegalStateException(message);
            }

            String transcript = extractTranscriptFromGrpcFrames(body);
            if (transcript.trim().isEmpty()) {
                throw new IllegalStateException("NVIDIA ASR did not hear speech in this file.");
            }
            return transcript;
        }
    }

    private byte[] buildRecognizeRequest(byte[] pcm16le) {
        byte[] config = buildRecognitionConfig();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeBytesField(out, 1, config);
        writeBytesField(out, 2, pcm16le);
        return out.toByteArray();
    }

    private byte[] buildRecognitionConfig() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeVarintField(out, 1, 1); // LINEAR_PCM
        writeVarintField(out, 2, 16000);
        writeStringField(out, 3, "en-US");
        writeVarintField(out, 4, 1);
        writeVarintField(out, 11, 1);
        return out.toByteArray();
    }

    private byte[] frameGrpcMessage(byte[] message) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0);
        out.write((message.length >> 24) & 0xff);
        out.write((message.length >> 16) & 0xff);
        out.write((message.length >> 8) & 0xff);
        out.write(message.length & 0xff);
        out.write(message, 0, message.length);
        return out.toByteArray();
    }

    private String extractTranscriptFromGrpcFrames(byte[] body) {
        StringBuilder transcript = new StringBuilder();
        int offset = 0;
        while (offset + 5 <= body.length) {
            int compressed = body[offset] & 0xff;
            int length = ((body[offset + 1] & 0xff) << 24)
                    | ((body[offset + 2] & 0xff) << 16)
                    | ((body[offset + 3] & 0xff) << 8)
                    | (body[offset + 4] & 0xff);
            offset += 5;
            if (compressed != 0) {
                throw new IllegalStateException("NVIDIA ASR returned compressed gRPC audio results.");
            }
            if (length < 0 || offset + length > body.length) {
                throw new IllegalStateException("NVIDIA ASR returned an invalid gRPC response.");
            }
            String part = parseRecognizeResponse(body, offset, offset + length);
            if (!part.isEmpty()) {
                if (transcript.length() > 0) transcript.append(' ');
                transcript.append(part);
            }
            offset += length;
        }
        return transcript.toString().trim();
    }

    private String parseRecognizeResponse(byte[] data, int start, int end) {
        StringBuilder transcript = new StringBuilder();
        ProtoReader reader = new ProtoReader(data, start, end);
        while (reader.hasRemaining()) {
            int tag = reader.readVarint();
            int field = tag >> 3;
            int wireType = tag & 7;
            if (field == 1 && wireType == 2) {
                byte[] result = reader.readBytes();
                String part = parseSpeechRecognitionResult(result);
                if (!part.isEmpty()) {
                    if (transcript.length() > 0) transcript.append(' ');
                    transcript.append(part);
                }
            } else {
                reader.skip(wireType);
            }
        }
        return transcript.toString();
    }

    private String parseSpeechRecognitionResult(byte[] data) {
        StringBuilder transcript = new StringBuilder();
        ProtoReader reader = new ProtoReader(data, 0, data.length);
        while (reader.hasRemaining()) {
            int tag = reader.readVarint();
            int field = tag >> 3;
            int wireType = tag & 7;
            if (field == 1 && wireType == 2) {
                byte[] alternative = reader.readBytes();
                String part = parseSpeechAlternative(alternative);
                if (!part.isEmpty()) {
                    if (transcript.length() > 0) transcript.append(' ');
                    transcript.append(part);
                }
            } else {
                reader.skip(wireType);
            }
        }
        return transcript.toString();
    }

    private String parseSpeechAlternative(byte[] data) {
        ProtoReader reader = new ProtoReader(data, 0, data.length);
        while (reader.hasRemaining()) {
            int tag = reader.readVarint();
            int field = tag >> 3;
            int wireType = tag & 7;
            if (field == 1 && wireType == 2) {
                return new String(reader.readBytes(), StandardCharsets.UTF_8).trim();
            }
            reader.skip(wireType);
        }
        return "";
    }

    private void writeVarintField(ByteArrayOutputStream out, int fieldNumber, int value) {
        writeVarint(out, (fieldNumber << 3) | 0);
        writeVarint(out, value);
    }

    private void writeStringField(ByteArrayOutputStream out, int fieldNumber, String value) {
        writeBytesField(out, fieldNumber, value.getBytes(StandardCharsets.UTF_8));
    }

    private void writeBytesField(ByteArrayOutputStream out, int fieldNumber, byte[] value) {
        writeVarint(out, (fieldNumber << 3) | 2);
        writeVarint(out, value.length);
        out.write(value, 0, value.length);
    }

    private void writeVarint(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7f) != 0) {
            out.write((value & 0x7f) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    private static class ProtoReader {
        private final byte[] data;
        private int offset;
        private final int end;

        ProtoReader(byte[] data, int start, int end) {
            this.data = data;
            this.offset = start;
            this.end = end;
        }

        boolean hasRemaining() {
            return offset < end;
        }

        int readVarint() {
            int result = 0;
            int shift = 0;
            while (offset < end && shift < 32) {
                int b = data[offset++] & 0xff;
                result |= (b & 0x7f) << shift;
                if ((b & 0x80) == 0) return result;
                shift += 7;
            }
            throw new IllegalStateException("Invalid protobuf varint.");
        }

        byte[] readBytes() {
            int length = readVarint();
            if (length < 0 || offset + length > end) {
                throw new IllegalStateException("Invalid protobuf length.");
            }
            byte[] value = new byte[length];
            System.arraycopy(data, offset, value, 0, length);
            offset += length;
            return value;
        }

        void skip(int wireType) {
            if (wireType == 0) {
                readVarint();
            } else if (wireType == 1) {
                offset += 8;
            } else if (wireType == 2) {
                int length = readVarint();
                offset += length;
            } else if (wireType == 5) {
                offset += 4;
            } else {
                throw new IllegalStateException("Unsupported protobuf wire type: " + wireType);
            }
            if (offset > end) {
                throw new IllegalStateException("Invalid protobuf skip.");
            }
        }
    }
}
