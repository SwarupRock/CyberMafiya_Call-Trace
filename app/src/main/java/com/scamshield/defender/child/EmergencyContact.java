package com.scamshield.defender.child;

public class EmergencyContact {
    public String id;
    public String name;
    public String phoneNumber;
    public long addedAt;

    public EmergencyContact() {
    }

    public EmergencyContact(String id, String name, String phoneNumber, long addedAt) {
        this.id = id;
        this.name = name;
        this.phoneNumber = phoneNumber;
        this.addedAt = addedAt;
    }

    public String maskedPhone() {
        if (phoneNumber == null || phoneNumber.length() <= 4) return "****";
        String tail = phoneNumber.substring(phoneNumber.length() - 4);
        String head = phoneNumber.startsWith("+") ? phoneNumber.substring(0, Math.min(3, phoneNumber.length())) : "";
        return head + "xxxxxx" + tail;
    }
}
