package com.scamshield.defender.model;

import com.google.gson.annotations.SerializedName;

/**
 * Model for the call_logs table in Supabase.
 * Records every analyzed call with its scam verdict.
 */
public class CallLog {

    /** Auto-generated UUID (set by Supabase) */
    public String id;

    /** User ID from Supabase Auth (set by RLS policy) */
    @SerializedName("user_id")
    public String userId;

    /** The caller's phone number */
    @SerializedName("phone_number")
    public String phoneNumber;

    /** Call duration in seconds */
    @SerializedName("call_duration_seconds")
    public int callDurationSeconds;

    /** AI-computed scam probability (0.0 to 1.0) */
    @SerializedName("scam_score")
    public float scamScore;

    /** Final verdict: was this call a scam? */
    @SerializedName("is_scam")
    public boolean isScam;

    /** Deepfake detection probability */
    @SerializedName("deepfake_score")
    public float deepfakeScore;

    /** Timestamp of analysis (auto-set by Supabase default) */
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
    }

    @Override
    public String toString() {
        return "CallLog{" + phoneNumber + ", scam=" + isScam +
                ", score=" + scamScore + "}";
    }
}
