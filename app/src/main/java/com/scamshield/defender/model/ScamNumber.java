package com.scamshield.defender.model;

import com.google.gson.annotations.SerializedName;

/**
 * Model for the scam_numbers table in Supabase.
 * Community crowd-sourced scam number intelligence.
 * Numbers reported by multiple users get higher trust.
 */
public class ScamNumber {

    /** Phone number (primary key) */
    @SerializedName("phone_number")
    public String phone_number;

    /** Total reports from all users */
    @SerializedName("total_reports")
    public int total_reports;

    /** Average scam confidence score across all reports */
    @SerializedName("avg_scam_score")
    public float avg_scam_score;

    /** First time this number was reported */
    @SerializedName("first_reported")
    public String firstReported;

    /** Most recent report */
    @SerializedName("last_reported")
    public String lastReported;

    /** Manually verified by admin/moderator */
    @SerializedName("is_verified_scam")
    public boolean is_verified_scam;

    public ScamNumber() {}

    /**
     * Returns a threat level based on community reports.
     */
    public String getThreatLevel() {
        if (is_verified_scam) return "VERIFIED SCAM";
        if (total_reports >= 10) return "HIGH THREAT";
        if (total_reports >= 5) return "MEDIUM THREAT";
        if (total_reports >= 2) return "SUSPICIOUS";
        return "LOW THREAT";
    }

    @Override
    public String toString() {
        return "ScamNumber{" + phone_number +
                ", reports=" + total_reports +
                ", verified=" + is_verified_scam + "}";
    }
}
