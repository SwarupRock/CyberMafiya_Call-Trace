package com.scamshield.defender.child;

import java.util.ArrayList;
import java.util.List;

public class ChildRiskResult {
    public boolean isChildDetected;
    public boolean isExtractionAttempt;
    public float riskScore;
    public float nvidiaConfidenceScore;
    public String reason;
    public List<String> matchedChildSignals = new ArrayList<>();
    public List<String> matchedExtractionPatterns = new ArrayList<>();

    public boolean shouldAlert() {
        return isChildDetected && isExtractionAttempt;
    }
}
