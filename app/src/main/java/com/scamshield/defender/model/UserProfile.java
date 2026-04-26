package com.scamshield.defender.model;

public class UserProfile {
    private String uid;
    private String phoneNumber;
    private String email;
    private String country;
    private long createdAt;

    public UserProfile() {
        // Required for Firestore
    }

    public UserProfile(String uid, String phoneNumber, String email, String country, long createdAt) {
        this.uid = uid;
        this.phoneNumber = phoneNumber;
        this.email = email;
        this.country = country;
        this.createdAt = createdAt;
    }

    public String getUid() { return uid; }
    public void setUid(String uid) { this.uid = uid; }

    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
}
