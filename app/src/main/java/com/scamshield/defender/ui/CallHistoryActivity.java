package com.scamshield.defender.ui;

import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.ScaleAnimation;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.scamshield.defender.R;
import com.scamshield.defender.model.CallLog;
import com.scamshield.defender.network.FirestoreHelper;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * ═══════════════════════════════════════════════════════════════════
 * CallHistoryActivity — Intelligence Log
 * ═══════════════════════════════════════════════════════════════════
 *
 * Shows all analyzed calls with their transcripts, scam scores,
 * and blocked status. Users can filter by ALL, SCAM, or BLOCKED.
 */
public class CallHistoryActivity extends AppCompatActivity {

    private static final int MAX_ENTRIES = 50;

    private LinearLayout layoutCallEntries;
    private LinearLayout layoutEmpty;
    private ProgressBar progressLoading;
    private TextView tvTotalCount, tvStatTotal, tvStatScam, tvStatBlocked;
    private Button btnFilterAll, btnFilterScam, btnFilterBlocked;
    private Button btnBack;

    private List<CallLog> allLogs;
    private String currentFilter = "all"; // "all", "scam", "blocked"

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_call_history);

        bindViews();
        setupListeners();
        updateFilterButtons();
        loadCallHistory();
    }

    private void bindViews() {
        layoutCallEntries = findViewById(R.id.layout_call_entries);
        layoutEmpty = findViewById(R.id.layout_empty);
        progressLoading = findViewById(R.id.progress_loading);
        tvTotalCount = findViewById(R.id.tv_total_count);
        tvStatTotal = findViewById(R.id.tv_stat_total);
        tvStatScam = findViewById(R.id.tv_stat_scam);
        tvStatBlocked = findViewById(R.id.tv_stat_blocked);
        btnFilterAll = findViewById(R.id.btn_filter_all);
        btnFilterScam = findViewById(R.id.btn_filter_scam);
        btnFilterBlocked = findViewById(R.id.btn_filter_blocked);
        btnBack = findViewById(R.id.btn_back);
    }

    private void setupListeners() {
        btnBack.setOnClickListener(v -> finish());

        btnFilterAll.setOnClickListener(v -> {
            currentFilter = "all";
            updateFilterButtons();
            renderEntries();
        });

        btnFilterScam.setOnClickListener(v -> {
            currentFilter = "scam";
            updateFilterButtons();
            renderEntries();
        });

        btnFilterBlocked.setOnClickListener(v -> {
            currentFilter = "blocked";
            updateFilterButtons();
            renderEntries();
        });
    }

    private void updateFilterButtons() {
        // ALL button
        if (currentFilter.equals("all")) {
            btnFilterAll.setBackgroundResource(R.drawable.filter_active_cyan);
            btnFilterAll.setTextColor(getColor(R.color.obsidian_black));
        } else {
            btnFilterAll.setBackgroundResource(R.drawable.menu_button_cyan);
            btnFilterAll.setTextColor(getColor(R.color.cyber_cyan));
        }

        // SCAM button
        if (currentFilter.equals("scam")) {
            btnFilterScam.setBackgroundResource(R.drawable.filter_active_red);
            btnFilterScam.setTextColor(getColor(R.color.obsidian_black));
        } else {
            btnFilterScam.setBackgroundResource(R.drawable.menu_button_red);
            btnFilterScam.setTextColor(getColor(R.color.neon_crimson));
        }

        // BLOCKED button
        if (currentFilter.equals("blocked")) {
            btnFilterBlocked.setBackgroundResource(R.drawable.filter_active_amber);
            btnFilterBlocked.setTextColor(getColor(R.color.obsidian_black));
        } else {
            btnFilterBlocked.setBackgroundResource(R.drawable.menu_button_cyan);
            btnFilterBlocked.setTextColor(getColor(R.color.neon_amber));
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // DATA LOADING
    // ═══════════════════════════════════════════════════════════════

    private void loadCallHistory() {
        progressLoading.setVisibility(View.VISIBLE);
        layoutEmpty.setVisibility(View.GONE);
        layoutCallEntries.removeAllViews();

        FirestoreHelper.getInstance().getCallHistory(MAX_ENTRIES,
                new FirestoreHelper.DataCallback<List<CallLog>>() {
                    @Override
                    public void onSuccess(List<CallLog> logs) {
                        allLogs = logs;
                        progressLoading.setVisibility(View.GONE);
                        updateStats();
                        renderEntries();
                    }

                    @Override
                    public void onError(String error) {
                        progressLoading.setVisibility(View.GONE);
                        layoutEmpty.setVisibility(View.VISIBLE);
                    }
                });
    }

    private void updateStats() {
        if (allLogs == null) return;

        int total = allLogs.size();
        int scam = 0;
        int blocked = 0;

        for (CallLog log : allLogs) {
            if (log.isScam) scam++;
            if (log.blocked) blocked++;
        }

        tvStatTotal.setText(String.valueOf(total));
        tvStatScam.setText(String.valueOf(scam));
        tvStatBlocked.setText(String.valueOf(blocked));
        tvTotalCount.setText(total + " calls");
    }

    // ═══════════════════════════════════════════════════════════════
    // RENDER ENTRIES
    // ═══════════════════════════════════════════════════════════════

    private void renderEntries() {
        if (allLogs == null) return;

        layoutCallEntries.removeAllViews();
        int count = 0;

        for (int i = 0; i < allLogs.size(); i++) {
            CallLog log = allLogs.get(i);

            // Apply filter
            if (currentFilter.equals("scam") && !log.isScam) continue;
            if (currentFilter.equals("blocked") && !log.blocked) continue;

            addCallEntryView(log, i);
            count++;
        }

        if (count == 0) {
            layoutEmpty.setVisibility(View.VISIBLE);
        } else {
            layoutEmpty.setVisibility(View.GONE);
        }
    }

    private void addCallEntryView(CallLog log, int index) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.section_card_bg);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.setAlpha(0f);

        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(0, 0, 0, dp(10));
        card.setLayoutParams(cardParams);

        // ── Row 1: Phone number + badges ──
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(android.view.Gravity.CENTER_VERTICAL);

        // Threat indicator dot
        View dot = new View(this);
        int dotColor;
        if (log.isScam) {
            dotColor = getColor(R.color.neon_crimson);
        } else if (log.scamScore > 0.4f) {
            dotColor = getColor(R.color.neon_amber);
        } else {
            dotColor = getColor(R.color.cyber_green);
        }
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(8), dp(8));
        dotParams.setMargins(0, 0, dp(8), 0);
        dot.setLayoutParams(dotParams);
        dot.setBackgroundResource(R.drawable.circle_dot);
        dot.setBackgroundTintList(android.content.res.ColorStateList.valueOf(dotColor));
        row1.addView(dot);

        // Phone number
        TextView tvNumber = new TextView(this);
        tvNumber.setText(maskNumber(log.phoneNumber));
        tvNumber.setTextColor(getColor(R.color.text_primary));
        tvNumber.setTextSize(15);
        tvNumber.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        LinearLayout.LayoutParams numberParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tvNumber.setLayoutParams(numberParams);
        row1.addView(tvNumber);

        // Scam badge
        if (log.isScam) {
            TextView badge = createBadge("SCAM", R.color.neon_crimson);
            row1.addView(badge);
        }

        // Blocked badge
        if (log.blocked) {
            TextView badge = createBadge("BLOCKED", R.color.neon_amber);
            LinearLayout.LayoutParams badgeParams =
                    (LinearLayout.LayoutParams) badge.getLayoutParams();
            badgeParams.setMargins(dp(6), 0, 0, 0);
            row1.addView(badge);
        }

        card.addView(row1);

        // ── Row 2: Date, duration, score ──
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams row2Params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        row2Params.setMargins(dp(16), dp(6), 0, 0);
        row2.setLayoutParams(row2Params);

        TextView tvDate = createMutedText(formatDate(log.timestamp));
        tvDate.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row2.addView(tvDate);

        if (log.callDurationSeconds > 0) {
            row2.addView(createMutedText(log.callDurationSeconds + "s"));
            row2.addView(createDividerDot());
        }

        TextView tvScore = createMutedText((int)(log.scamScore * 100) + "% threat");
        tvScore.setTextColor(dotColor);
        row2.addView(tvScore);

        card.addView(row2);

        // ── Row 3: Threat type ──
        if (log.threatType != null && !log.threatType.equals("none") && !log.threatType.isEmpty()) {
            TextView tvType = new TextView(this);
            tvType.setText("▸ " + log.threatType.replace("_", " ").toUpperCase());
            tvType.setTextColor(getColor(R.color.neon_amber));
            tvType.setTextSize(11);
            tvType.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            LinearLayout.LayoutParams typeParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            typeParams.setMargins(dp(16), dp(6), 0, 0);
            tvType.setLayoutParams(typeParams);
            card.addView(tvType);
        }

        // ── Row 4: Transcript (expandable) ──
        String transcript = log.transcript != null ? log.transcript.trim() : "";
        if (!transcript.isEmpty()) {
            // Collapsed preview
            TextView tvPreview = new TextView(this);
            tvPreview.setText(truncate(transcript, 80) + "  ▾ TAP TO EXPAND");
            tvPreview.setTextColor(getColor(R.color.text_dim));
            tvPreview.setTextSize(11);
            tvPreview.setTypeface(Typeface.MONOSPACE);
            LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            previewParams.setMargins(dp(16), dp(10), 0, 0);
            tvPreview.setLayoutParams(previewParams);
            tvPreview.setVisibility(View.VISIBLE);

            // Full transcript (hidden)
            TextView tvFull = new TextView(this);
            tvFull.setText("TRANSCRIPT:\n" + transcript);
            tvFull.setTextColor(getColor(R.color.text_secondary));
            tvFull.setTextSize(12);
            tvFull.setTypeface(Typeface.MONOSPACE);
            tvFull.setBackgroundResource(R.drawable.terminal_bg);
            tvFull.setPadding(dp(12), dp(10), dp(12), dp(10));
            LinearLayout.LayoutParams fullParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            fullParams.setMargins(dp(16), dp(6), 0, 0);
            tvFull.setLayoutParams(fullParams);
            tvFull.setVisibility(View.GONE);

            card.addView(tvPreview);
            card.addView(tvFull);

            // Toggle transcript on tap
            card.setOnClickListener(v -> {
                if (tvFull.getVisibility() == View.GONE) {
                    tvFull.setVisibility(View.VISIBLE);
                    tvPreview.setText(truncate(transcript, 80) + "  ▴ TAP TO COLLAPSE");
                    tvFull.setAlpha(0f);
                    tvFull.animate().alpha(1f).setDuration(200).start();
                } else {
                    tvFull.setVisibility(View.GONE);
                    tvPreview.setText(truncate(transcript, 80) + "  ▾ TAP TO EXPAND");
                }
            });
        }

        // ── Export CSV Button ──
        Button btnExport = new Button(this);
        btnExport.setText("📥 EXPORT CSV");
        btnExport.setTextColor(getColor(R.color.cyber_cyan));
        btnExport.setTextSize(10);
        btnExport.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        btnExport.setBackgroundResource(R.drawable.menu_button_cyan);
        btnExport.setAllCaps(false);
        LinearLayout.LayoutParams exportParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        exportParams.setMargins(0, dp(10), 0, 0);
        btnExport.setLayoutParams(exportParams);
        btnExport.setPadding(dp(12), dp(8), dp(12), dp(8));
        btnExport.setOnClickListener(v -> exportSingleCallToCsv(log));
        card.addView(btnExport);

        layoutCallEntries.addView(card);

        // Animate entry with stagger
        card.animate()
                .alpha(1f)
                .setStartDelay(index * 50L)
                .setDuration(250)
                .start();
    }

    // ═══════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════

    private TextView createBadge(String text, int colorRes) {
        TextView badge = new TextView(this);
        badge.setText(text);
        badge.setTextColor(getColor(colorRes));
        badge.setTextSize(9);
        badge.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        badge.setLetterSpacing(0.08f);
        badge.setBackgroundResource(R.drawable.badge_bg);
        badge.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                getColor(colorRes) & 0x33FFFFFF)); // 20% opacity
        badge.setPadding(dp(8), dp(3), dp(8), dp(3));
        return badge;
    }

    private TextView createMutedText(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(getColor(R.color.text_muted));
        tv.setTextSize(11);
        tv.setTypeface(Typeface.MONOSPACE);
        return tv;
    }

    private TextView createDividerDot() {
        TextView tv = new TextView(this);
        tv.setText("  ·  ");
        tv.setTextColor(getColor(R.color.text_muted));
        tv.setTextSize(11);
        tv.setTypeface(Typeface.MONOSPACE);
        return tv;
    }

    private String maskNumber(String number) {
        if (number == null || number.length() <= 4) return "UNKNOWN";
        return number.substring(0, 4) + "****" + number.substring(number.length() - 2);
    }

    private String formatDate(long timestamp) {
        if (timestamp == 0) return "—";
        SimpleDateFormat sdf = new SimpleDateFormat("dd MMM, HH:mm", Locale.US);
        return sdf.format(new Date(timestamp));
    }

    private String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max - 3) + "...";
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    // ═══════════════════════════════════════════════════════════════
    // CSV EXPORT
    // ═══════════════════════════════════════════════════════════════

    private void exportSingleCallToCsv(CallLog log) {
        try {
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), "CallTrace");
            if (!dir.exists()) dir.mkdirs();

            String fileName = "call_trace_" + log.phoneNumber.replaceAll("[^0-9]", "") + "_"
                    + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date(log.timestamp))
                    + ".csv";
            File file = new File(dir, fileName);

            FileWriter writer = new FileWriter(file);
            writer.write("Phone Number,Date,Duration (s),Scam Score,Is Scam,Threat Type,Risk Level,Blocked,Transcript\n");
            writer.write(escapeCsv(log.phoneNumber) + ",");
            writer.write(escapeCsv(formatDate(log.timestamp)) + ",");
            writer.write(log.callDurationSeconds + ",");
            writer.write(String.format(Locale.US, "%.2f", log.scamScore) + ",");
            writer.write((log.isScam ? "YES" : "NO") + ",");
            writer.write(escapeCsv(log.threatType != null ? log.threatType : "none") + ",");
            writer.write(escapeCsv(getRiskLabel(log.scamScore)) + ",");
            writer.write((log.blocked ? "YES" : "NO") + ",");
            writer.write(escapeCsv(log.transcript != null ? log.transcript : "") + "\n");
            writer.close();

            Toast.makeText(this, "✅ CSV exported to Downloads/CallTrace/" + fileName,
                    Toast.LENGTH_LONG).show();
        } catch (IOException e) {
            Toast.makeText(this, "❌ Export failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private String getRiskLabel(float score) {
        if (score >= 0.70f) return "HIGH RISK";
        if (score >= 0.40f) return "MEDIUM RISK";
        if (score >= 0.10f) return "LOW RISK";
        return "SAFE";
    }
}
