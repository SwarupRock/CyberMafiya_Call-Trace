package com.scamshield.defender.model;

import com.google.gson.annotations.SerializedName;

/**
 * Model for the blocked_numbers table in Supabase.
 * Tracks numbers blocked by individual users.
 */
public class BlockedNumber {

    public String id;

    @SerializedName("phone_number")
    public String phone_number;

    /** User who blocked this number (set by RLS policy) */
    @SerializedName("blocked_by")
    public String blockedBy;

    /** Reason for blocking: "auto_detected" or "user_reported" */
    public String reason;

    /** The scam confidence score that triggered the block */
    @SerializedName("scam_score")
    public float scam_score;

    /** Number of times this user blocked this number */
    @SerializedName("report_count")
    public int reportCount;

    @SerializedName("created_at")
    public String createdAt;

    public BlockedNumber() {}

    public BlockedNumber(String phoneNumber, String reason, float scamScore) {
        this.phone_number = phoneNumber;
        this.reason = reason;
        this.scam_score = scamScore;
        this.reportCount = 1;
    }

    @Override
    public String toString() {
        return "BlockedNumber{" + phone_number + ", reason=" + reason + "}";
    }
}
