package com.scamshield.defender.analyzer;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.annotations.SerializedName;

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

public class ScamAnalyzerAI {

    private static final String TAG = "ScamAnalyzerAI";
    private static final float LOCAL_ALERT_THRESHOLD = 0.40f;
    private static final float SCAM_CONFIRMED_THRESHOLD = 0.70f;
    private static final float CLOUD_TRIGGER_THRESHOLD = 0.30f;
    private static final String PREFS_NAME = "scam_shield_prefs";
    private static final String PREF_AI_PROVIDER = "ai_provider";
    private static final String PREF_GEMINI_API_KEY = "gemini_api_key";
    private static final String PREF_NVIDIA_API_KEY = "nvidia_api_key";
    private static final String PROVIDER_GEMINI = "gemini";
    private static final String PROVIDER_NVIDIA = "nvidia";
    private static final String GEMINI_MODEL = "gemini-2.0-flash";
    private static final String GEMINI_ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/" +
                    GEMINI_MODEL + ":generateContent?key=";
    private static final String NVIDIA_MODEL = "meta/llama-3.1-8b-instruct";
    private static final String NVIDIA_ENDPOINT = "https://integrate.api.nvidia.com/v1/chat/completions";

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
        String provider = prefs.getString(PREF_AI_PROVIDER, PROVIDER_GEMINI);
        String apiKey = getCloudApiKey(prefs, provider);
        if (apiKey == null || apiKey.trim().isEmpty()) {
            Log.w(TAG, "Cloud AI API key missing. Local analysis remains active.");
            return;
        }

        cloudExecutor.execute(() -> {
            try {
                CloudResponse cloud = PROVIDER_NVIDIA.equals(provider)
                        ? callNvidia(apiKey.trim(), transcript)
                        : callGemini(apiKey.trim(), transcript);
                applyCloudResult(cloud);
            } catch (Exception e) {
                Log.w(TAG, "Cloud analysis failed. Local analysis remains active: " + e.getMessage());
            }
        });
    }

    private String getCloudApiKey(SharedPreferences prefs, String provider) {
        if (PROVIDER_NVIDIA.equals(provider)) {
            return prefs.getString(PREF_NVIDIA_API_KEY, "");
        }
        return prefs.getString(PREF_GEMINI_API_KEY, "");
    }

    private CloudResponse callGemini(String apiKey, String transcript) throws Exception {
        URL url = new URL(GEMINI_ENDPOINT + apiKey);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);

        String prompt = "You are Call Trace analyzing an English phone-call transcript in real time. " +
                "Return only JSON with keys: is_scam boolean, confidence number 0-1, " +
                "threat_type string, reasoning string, keywords_found string array. " +
                "Flag OTP, bank or KYC impersonation, urgent account freeze, remote access, legal threats, " +
                "refund traps, and credential requests. Transcript: " + transcript;

        JsonObject textPart = new JsonObject();
        textPart.addProperty("text", prompt);

        JsonArray parts = new JsonArray();
        parts.add(textPart);

        JsonObject content = new JsonObject();
        content.add("parts", parts);

        JsonArray contents = new JsonArray();
        contents.add(content);

        JsonObject generationConfig = new JsonObject();
        generationConfig.addProperty("temperature", 0.1);
        generationConfig.addProperty("responseMimeType", "application/json");

        JsonObject body = new JsonObject();
        body.add("contents", contents);
        body.add("generationConfig", generationConfig);

        try (OutputStream os = conn.getOutputStream()) {
            byte[] input = gson.toJson(body).getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

        int code = conn.getResponseCode();
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream(),
                StandardCharsets.UTF_8));
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            response.append(line);
        }
        reader.close();
        conn.disconnect();

        if (code < 200 || code >= 300) {
            throw new IllegalStateException("Gemini HTTP " + code + ": " + response);
        }

        String modelText = extractGeminiText(response.toString());
        return gson.fromJson(modelText, CloudResponse.class);
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
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream(),
                StandardCharsets.UTF_8));
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            response.append(line);
        }
        reader.close();
        conn.disconnect();

        if (code < 200 || code >= 300) {
            throw new IllegalStateException("NVIDIA HTTP " + code + ": " + response);
        }

        String modelText = extractChatCompletionText(response.toString());
        return gson.fromJson(stripJsonFence(modelText), CloudResponse.class);
    }

    private String extractGeminiText(String responseJson) {
        JsonObject root = JsonParser.parseString(responseJson).getAsJsonObject();
        JsonArray candidates = root.getAsJsonArray("candidates");
        if (candidates == null || candidates.size() == 0) {
            throw new IllegalStateException("Gemini returned no candidates");
        }
        JsonObject content = candidates.get(0).getAsJsonObject().getAsJsonObject("content");
        JsonArray parts = content.getAsJsonArray("parts");
        if (parts == null || parts.size() == 0) {
            throw new IllegalStateException("Gemini returned no text parts");
        }
        return parts.get(0).getAsJsonObject().get("text").getAsString();
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

        if (cloud.confidence > currentScore) {
            currentScore = cloud.confidence;
        }

        AnalysisResult result = new AnalysisResult();
        result.confidence = currentScore;
        result.isScam = cloud.isScam;
        result.threatType = cloud.threatType != null ? cloud.threatType : threatCategory;
        result.reasoning = cloud.reasoning != null ? cloud.reasoning : "Cloud AI analysis";
        result.keywordsFound = cloud.keywordsFound != null && !cloud.keywordsFound.isEmpty()
                ? new ArrayList<>(cloud.keywordsFound)
                : new ArrayList<>(detectedKeywords);
        result.fromCloud = true;

        latestResult = result;
        notifyListeners(result);

        Log.i(TAG, "Cloud verdict: " + (cloud.isScam ? "SCAM" : "SAFE")
                + " (" + (cloud.confidence * 100) + "%)");
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
        String apiKey = prefs.getString(PREF_GEMINI_API_KEY, "");
        if (apiKey == null || apiKey.trim().isEmpty()) {
            callback.onError("Gemini API key is required for audio upload. NVIDIA is enabled for live transcript analysis only.");
            return;
        }

        cloudExecutor.execute(() -> {
            try {
                String base64Audio = android.util.Base64.encodeToString(audioData, android.util.Base64.NO_WRAP);

                String prompt = "You are Call Trace. Analyze this audio file for scam indicators. " +
                    "Transcribe all speech in the audio. Then determine if this is a scam call. " +
                    "Return ONLY valid JSON with these keys: " +
                    "transcript (string - full transcription of all speech), " +
                    "is_scam (boolean), " +
                    "confidence (number 0-1), " +
                    "threat_type (string - one of: banking, kyc, urgency, authority, lottery, tech_support, threats, call_bomber, none), " +
                    "risk_level (string - one of: HIGH RISK, MEDIUM RISK, LOW RISK, SAFE), " +
                    "reasoning (string - brief explanation), " +
                    "keywords_found (string array). " +
                    "Flag OTP requests, bank impersonation, KYC fraud, urgent threats, remote access, legal threats, refund traps.";

                JsonObject textPart = new JsonObject();
                textPart.addProperty("text", prompt);

                JsonObject audioInlineData = new JsonObject();
                audioInlineData.addProperty("mime_type", mimeType);
                audioInlineData.addProperty("data", base64Audio);
                JsonObject audioPart = new JsonObject();
                audioPart.add("inline_data", audioInlineData);

                JsonArray parts = new JsonArray();
                parts.add(textPart);
                parts.add(audioPart);

                JsonObject content = new JsonObject();
                content.add("parts", parts);
                JsonArray contents = new JsonArray();
                contents.add(content);

                JsonObject genConfig = new JsonObject();
                genConfig.addProperty("temperature", 0.1);
                genConfig.addProperty("responseMimeType", "application/json");

                JsonObject body = new JsonObject();
                body.add("contents", contents);
                body.add("generationConfig", genConfig);

                URL url = new URL(GEMINI_ENDPOINT + apiKey.trim());
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(30000);
                conn.setReadTimeout(90000);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);

                byte[] jsonPayload = gson.toJson(body).getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(jsonPayload, 0, jsonPayload.length);
                }

                // ── Retry loop for rate-limit (429) errors ──
                int maxRetries = 2;
                int retryCount = 0;
                int code;
                StringBuilder response;

                while (true) {
                    code = conn.getResponseCode();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(
                        code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream(),
                        StandardCharsets.UTF_8));
                    response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();
                    conn.disconnect();

                    if (code == 429 && retryCount < maxRetries) {
                        retryCount++;
                        long waitMs = (long) (Math.pow(2, retryCount) * 1000); // 2s, 4s
                        Log.w(TAG, "Rate limited (429), retry " + retryCount + "/" + maxRetries
                                + " after " + waitMs + "ms");
                        callback.onError("RETRY: API rate limited — retrying in " + (waitMs/1000) + "s...");
                        Thread.sleep(waitMs);

                        // Re-open connection for retry
                        conn = (HttpURLConnection) url.openConnection();
                        conn.setRequestMethod("POST");
                        conn.setConnectTimeout(30000);
                        conn.setReadTimeout(90000);
                        conn.setRequestProperty("Content-Type", "application/json");
                        conn.setDoOutput(true);
                        try (OutputStream retryOs = conn.getOutputStream()) {
                            retryOs.write(jsonPayload, 0, jsonPayload.length);
                        }
                        continue;
                    }
                    break;
                }

                // ── Handle non-success responses with clean messages ──
                if (code < 200 || code >= 300) {
                    String errorMsg;
                    switch (code) {
                        case 429:
                            errorMsg = "API quota exceeded — please wait 1-2 minutes and try again";
                            break;
                        case 401:
                        case 403:
                            errorMsg = "Invalid API key — check your Gemini API key in settings";
                            break;
                        case 400:
                            errorMsg = "Audio format not supported — try MP3, WAV, or M4A";
                            break;
                        default:
                            errorMsg = "Server error (HTTP " + code + ") — try again later";
                            break;
                    }
                    throw new IllegalStateException(errorMsg);
                }

                String modelText = extractGeminiText(response.toString());
                AudioFileResponse audioResp = gson.fromJson(modelText, AudioFileResponse.class);

                AnalysisResult result = new AnalysisResult();
                result.confidence = audioResp.confidence;
                result.isScam = audioResp.isScam;
                result.threatType = audioResp.threatType != null ? audioResp.threatType : "none";
                result.reasoning = audioResp.reasoning != null ? audioResp.reasoning : "";
                result.keywordsFound = audioResp.keywordsFound != null ? audioResp.keywordsFound : new ArrayList<>();
                result.fromCloud = true;

                String transcript = audioResp.transcript != null ? audioResp.transcript : "No speech detected";

                callback.onTranscriptReady(transcript);
                callback.onAnalysisComplete(result, transcript);

                Log.i(TAG, "Audio file analysis: " + (audioResp.isScam ? "SCAM" : "SAFE")
                        + " (" + (audioResp.confidence * 100) + "%) Risk: " + audioResp.riskLevel);

            } catch (Exception e) {
                Log.e(TAG, "Audio file analysis failed", e);
                callback.onError("Analysis failed: " + e.getMessage());
            }
        });
    }
}
