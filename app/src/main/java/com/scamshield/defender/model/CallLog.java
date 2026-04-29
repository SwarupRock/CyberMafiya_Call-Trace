package com.scamshield.defender.model;

import com.google.gson.annotations.SerializedName;
import com.google.firebase.Timestamp;

import java.util.HashMap;
import java.util.Map;

/**
 * Model for call log entries stored in Firestore.
 * Records every analyzed call with its scam verdict and transcript.
 */
public class CallLog {

    public String id;

    @SerializedName("user_id")
    public String userId;

    @SerializedName("phone_number")
    public String phoneNumber;

    @SerializedName("call_duration_seconds")
    public int callDurationSeconds;

    @SerializedName("scam_score")
    public float scamScore;

    @SerializedName("is_scam")
    public boolean isScam;

    @SerializedName("deepfake_score")
    public float deepfakeScore;

    /** Full transcript of the call */
    @SerializedName("transcript")
    public String transcript;

    /** Scam threat category (banking, kyc, urgency, authority, etc.) */
    @SerializedName("threat_type")
    public String threatType;

    /** Unix timestamp in millis */
    @SerializedName("timestamp")
    public long timestamp;

    /** Whether the number was auto-blocked after the call */
    @SerializedName("blocked")
    public boolean blocked;

    @SerializedName("analyzed_at")
    public String analyzedAt;

    public CallLog() {}

    public CallLog(String phoneNumber, int duration, float scamScore,
                   boolean isScam, float deepfakeScore) {
        this.phoneNumber = phoneNumber;
        this.callDurationSeconds = duration;
        this.scamScore = scamScore;
        this.isScam = isScam;
        this.deepfakeScore = deepfakeScore;
        this.timestamp = System.currentTimeMillis();
    }

    /** Convert to Firestore-compatible Map */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("phoneNumber", phoneNumber != null ? phoneNumber : "");
        map.put("callDurationSeconds", callDurationSeconds);
        map.put("scamScore", scamScore);
        map.put("isScam", isScam);
        map.put("deepfakeScore", deepfakeScore);
        map.put("transcript", transcript != null ? transcript : "");
        map.put("threatType", threatType != null ? threatType : "none");
        map.put("timestamp", timestamp > 0 ? timestamp : System.currentTimeMillis());
        map.put("blocked", blocked);
        return map;
    }

    /** Create from Firestore document data */
    public static CallLog fromMap(String docId, Map<String, Object> data) {
        CallLog log = new CallLog();
        log.id = docId;
        log.phoneNumber = getStr(data, "phoneNumber");
        log.callDurationSeconds = getInt(data, "callDurationSeconds");
        log.scamScore = getFloat(data, "scamScore");
        log.isScam = getBool(data, "isScam");
        log.deepfakeScore = getFloat(data, "deepfakeScore");
        log.transcript = getStr(data, "transcript");
        log.threatType = getStr(data, "threatType");
        log.timestamp = getLong(data, "timestamp");
        log.blocked = getBool(data, "blocked");
        return log;
    }

    private static String getStr(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v != null ? v.toString() : "";
    }

    private static int getInt(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v instanceof Number) return ((Number) v).intValue();
        return 0;
    }

    private static float getFloat(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v instanceof Number) return ((Number) v).floatValue();
        return 0f;
    }

    private static long getLong(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v instanceof Number) return ((Number) v).longValue();
        if (v instanceof Timestamp) return ((Timestamp) v).toDate().getTime();
        if (v instanceof java.util.Date) return ((java.util.Date) v).getTime();
        if (v instanceof String) {
            try {
                return Long.parseLong((String) v);
            } catch (NumberFormatException ignored) {
            }
        }
        return 0L;
    }

    private static boolean getBool(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v instanceof Boolean) return (Boolean) v;
        return false;
    }

    @Override
    public String toString() {
        return "CallLog{" + phoneNumber + ", scam=" + isScam +
                ", score=" + scamScore + "}";
    }
}
