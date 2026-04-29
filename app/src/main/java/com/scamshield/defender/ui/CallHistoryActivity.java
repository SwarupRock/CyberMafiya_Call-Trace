package com.scamshield.defender.ui;

import android.content.Context;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.graphics.drawable.ColorDrawable;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.animation.Animation;
import android.view.animation.ScaleAnimation;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.scamshield.defender.R;
import com.scamshield.defender.model.CallLog;
import com.scamshield.defender.network.FirestoreHelper;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import android.provider.MediaStore;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
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

    private static final String TAG = "CallHistoryActivity";
    private static final int MAX_ENTRIES = 50;
    private static final String PREFS_NAME = "scam_shield_prefs";
    private static final String PREF_NVIDIA_LLM_API_KEY = "nvidia_llm_api_key";
    private static final String NVIDIA_MODEL = "meta/llama-3.1-8b-instruct";
    private static final String NVIDIA_ENDPOINT = "https://integrate.api.nvidia.com/v1/chat/completions";

    private static final LanguageOption[] EXPORT_LANGUAGES = {
            new LanguageOption("Original transcript", "original", "English", false),
            new LanguageOption("Kannada", "kn", "Kannada", true),
            new LanguageOption("Hindi", "hi", "Hindi", true),
            new LanguageOption("Telugu", "te", "Telugu", true),
            new LanguageOption("Tamil", "ta", "Tamil", true)
    };

    private LinearLayout layoutCallEntries;
    private LinearLayout layoutEmpty;
    private ProgressBar progressLoading;
    private TextView tvTotalCount, tvStatTotal, tvStatScam, tvStatBlocked;
    private Button btnFilterAll, btnFilterScam, btnFilterBlocked, btnFilterChildSafety;
    private Button btnBack;
    private final Gson gson = new Gson();

    private List<CallLog> allLogs;
    private String currentFilter = "all"; // "all", "scam", "blocked", "child_safety"

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.activity_call_history);
            bindViews();
            setupListeners();
            updateFilterButtons();
            loadCallHistory();
        } catch (Exception e) {
            Log.e(TAG, "Call history screen failed to open", e);
            showHistoryErrorView("Call History could not open. " + safeError(e.getMessage()));
        }
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
        btnFilterChildSafety = findViewById(R.id.btn_filter_child_safety);
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

        btnFilterChildSafety.setOnClickListener(v -> {
            currentFilter = "child_safety";
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

        if (currentFilter.equals("child_safety")) {
            btnFilterChildSafety.setBackgroundResource(R.drawable.filter_active_red);
            btnFilterChildSafety.setTextColor(getColor(R.color.obsidian_black));
        } else {
            btnFilterChildSafety.setBackgroundResource(R.drawable.menu_button_red);
            btnFilterChildSafety.setTextColor(getColor(R.color.neon_crimson));
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // DATA LOADING
    // ═══════════════════════════════════════════════════════════════

    private void loadCallHistory() {
        progressLoading.setVisibility(View.VISIBLE);
        layoutEmpty.setVisibility(View.GONE);
        layoutCallEntries.removeAllViews();

        try {
            FirestoreHelper.getInstance().getCallHistory(MAX_ENTRIES,
                    new FirestoreHelper.DataCallback<List<CallLog>>() {
                        @Override
                        public void onSuccess(List<CallLog> logs) {
                            try {
                                allLogs = logs != null ? logs : new ArrayList<>();
                                progressLoading.setVisibility(View.GONE);
                                updateStats();
                                renderEntries();
                            } catch (Exception e) {
                                Log.e(TAG, "Could not render call history", e);
                                showLoadFailure("Could not render call history: " + safeError(e.getMessage()));
                            }
                        }

                        @Override
                        public void onError(String error) {
                            showLoadFailure("Could not load call history: " + safeError(error));
                        }
                    });
        } catch (Exception e) {
            Log.e(TAG, "Could not start call history load", e);
            showLoadFailure("Could not load call history: " + safeError(e.getMessage()));
        }
    }

    private void updateStats() {
        if (allLogs == null) return;

        int total = allLogs.size();
        int scam = 0;
        int blocked = 0;

        for (CallLog log : allLogs) {
            if (log == null) continue;
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
            if (log == null) continue;

            // Apply filter
            if (currentFilter.equals("scam") && !log.isScam) continue;
            if (currentFilter.equals("blocked") && !log.blocked) continue;
            if (currentFilter.equals("child_safety") && !isChildSafetyLog(log)) continue;

            try {
                addCallEntryView(log, i);
            } catch (Exception e) {
                Log.w(TAG, "Skipping bad call history row: " + log.id, e);
                continue;
            }
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
        if (isChildSafetyLog(log)) {
            TextView badge = createBadge("CHILD ALERT", R.color.neon_crimson);
            row1.addView(badge);
        } else if (log.isScam) {
            TextView badge = createBadge("SCAM", R.color.neon_crimson);
            row1.addView(badge);
        }

        // Blocked badge
        if (log.blocked) {
            TextView badge = createBadge("BLOCKED", R.color.neon_amber);
            LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            badgeParams.setMargins(dp(6), 0, 0, 0);
            row1.addView(badge, badgeParams);
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
            tvType.setText("> " + log.threatType.replace("_", " ").toUpperCase());
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
            tvPreview.setText(truncate(transcript, 80) + "  TAP TO EXPAND");
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
                    tvPreview.setText(truncate(transcript, 80) + "  TAP TO COLLAPSE");
                    tvFull.setAlpha(0f);
                    tvFull.animate().alpha(1f).setDuration(200).start();
                } else {
                    tvFull.setVisibility(View.GONE);
                    tvPreview.setText(truncate(transcript, 80) + "  TAP TO EXPAND");
                }
            });
        }

        // ── Export CSV Button ──
        Button btnExport = new Button(this);
        btnExport.setText("EXPORT CSV");
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
        btnExport.setOnClickListener(v ->
                exportSingleCallToCsv(log, EXPORT_LANGUAGES[0], btnExport));
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
        tv.setText("  -  ");
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
        if (timestamp == 0) return "-";
        SimpleDateFormat sdf = new SimpleDateFormat("dd MMM, HH:mm", Locale.US);
        return sdf.format(new Date(timestamp));
    }

    private String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max - 3) + "...";
    }

    private boolean isChildSafetyLog(CallLog log) {
        return log != null
                && log.threatType != null
                && log.threatType.equalsIgnoreCase("child_safety");
    }

    private void showLoadFailure(String message) {
        if (isFinishing() || isDestroyed()) return;
        progressLoading.setVisibility(View.GONE);
        allLogs = new ArrayList<>();
        updateStats();
        layoutEmpty.setVisibility(View.VISIBLE);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void showHistoryErrorView(String messageText) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(22), dp(22), dp(22), dp(22));
        root.setBackgroundColor(android.graphics.Color.BLACK);

        TextView title = new TextView(this);
        title.setText("CALL HISTORY");
        title.setTextColor(android.graphics.Color.CYAN);
        title.setTextSize(22);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        root.addView(title);

        TextView message = new TextView(this);
        message.setText(messageText);
        message.setTextColor(android.graphics.Color.WHITE);
        message.setGravity(Gravity.CENTER);
        message.setPadding(0, dp(14), 0, dp(14));
        root.addView(message);

        Button back = new Button(this);
        back.setText("BACK");
        back.setOnClickListener(v -> finish());
        root.addView(back);
        setContentView(root);
    }

    private String safeError(String error) {
        return error == null || error.trim().isEmpty() ? "Unknown error" : error;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    // ═══════════════════════════════════════════════════════════════
    // CSV EXPORT
    // ═══════════════════════════════════════════════════════════════

    private void showExportLanguagePicker(CallLog log, Button exportButton) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundResource(R.drawable.login_panel_bg);
        panel.setPadding(dp(18), dp(18), dp(18), dp(16));
        panel.setAlpha(0f);
        panel.setScaleX(0.96f);
        panel.setScaleY(0.96f);

        TextView eyebrow = new TextView(this);
        eyebrow.setText("CSV EXPORT");
        eyebrow.setTextColor(getColor(R.color.cyber_cyan));
        eyebrow.setTextSize(10);
        eyebrow.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        eyebrow.setLetterSpacing(0.16f);
        panel.addView(eyebrow);

        TextView title = new TextView(this);
        title.setText("Download Transcript");
        title.setTextColor(getColor(R.color.text_primary));
        title.setTextSize(20);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        titleParams.setMargins(0, dp(6), 0, 0);
        panel.addView(title, titleParams);

        TextView message = new TextView(this);
        message.setText("Choose the language for the CSV transcript.");
        message.setTextColor(getColor(R.color.text_secondary));
        message.setTextSize(12);
        message.setTypeface(Typeface.MONOSPACE);
        message.setLineSpacing(dp(2), 1.0f);
        LinearLayout.LayoutParams messageParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        messageParams.setMargins(0, dp(8), 0, dp(12));
        panel.addView(message, messageParams);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(panel)
                .create();

        for (LanguageOption language : EXPORT_LANGUAGES) {
            Button option = new Button(this);
            option.setAllCaps(false);
            option.setGravity(Gravity.CENTER_VERTICAL);
            option.setText(language.label);
            option.setTextColor(getColor(language.requiresTranslation
                    ? R.color.cyber_cyan
                    : R.color.cyber_green));
            option.setTextSize(12);
            option.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            option.setBackgroundResource(R.drawable.menu_button_cyan);
            option.setPadding(dp(14), 0, dp(14), 0);
            LinearLayout.LayoutParams optionParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(46));
            optionParams.setMargins(0, dp(8), 0, 0);
            panel.addView(option, optionParams);

            option.setOnClickListener(v -> {
                dialog.dismiss();
                exportSingleCallToCsv(log, language, exportButton);
            });
        }

        Button cancel = new Button(this);
        cancel.setAllCaps(false);
        cancel.setText("Cancel");
        cancel.setTextColor(getColor(R.color.text_dim));
        cancel.setTextSize(12);
        cancel.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        cancel.setBackgroundResource(R.drawable.btn_outline_bg);
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(46));
        cancelParams.setMargins(0, dp(12), 0, 0);
        panel.addView(cancel, cancelParams);
        cancel.setOnClickListener(v -> dialog.dismiss());

        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));
            window.setDimAmount(0.76f);
            window.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.90f),
                    android.view.WindowManager.LayoutParams.WRAP_CONTENT);
        }

        panel.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(180)
                .start();
    }

    private void exportSingleCallToCsv(CallLog log, LanguageOption language, Button exportButton) {
        String originalTranscript = log.transcript != null ? log.transcript.trim() : "";
        if (language.requiresTranslation && originalTranscript.isEmpty()) {
            Toast.makeText(this, "No transcript available to translate", Toast.LENGTH_LONG).show();
            return;
        }
        if (language.requiresTranslation && !hasNvidiaAnalysisKey()) {
            Toast.makeText(this, "NVIDIA LLM key required for translated CSV export",
                    Toast.LENGTH_LONG).show();
            return;
        }

        exportButton.setEnabled(false);
        exportButton.setText(language.requiresTranslation ? "Translating..." : "Exporting...");

        new Thread(() -> {
            try {
                String exportTranscript = language.requiresTranslation
                        ? translateTranscript(originalTranscript, language)
                        : originalTranscript;
                String fileName = writeCallCsv(log, language, exportTranscript, originalTranscript);
                runOnUiThread(() -> {
                    exportButton.setEnabled(true);
                    exportButton.setText("EXPORT CSV");
                    Toast.makeText(this, "CSV exported to Downloads/CallTrace/" + fileName,
                            Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                Log.e(TAG, "CSV export failed", e);
                runOnUiThread(() -> {
                    exportButton.setEnabled(true);
                    exportButton.setText("EXPORT CSV");
                    Toast.makeText(this, "Export failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private String writeCallCsv(CallLog log, LanguageOption language,
                                String exportTranscript, String originalTranscript) throws IOException {
        String safeNumber = log.phoneNumber != null
                ? log.phoneNumber.replaceAll("[^0-9A-Za-z]", "")
                : "unknown";
        if (safeNumber.isEmpty()) safeNumber = "unknown";

        String fileName = "call_trace_" + safeNumber + "_"
                + language.code + "_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date(log.timestamp))
                + ".csv";

        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
        values.put(MediaStore.Downloads.MIME_TYPE, "text/csv");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/CallTrace");
            values.put(MediaStore.Downloads.IS_PENDING, 1);
        }

        Uri collection = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? MediaStore.Downloads.EXTERNAL_CONTENT_URI
                : MediaStore.Files.getContentUri("external");
        Uri fileUri = getContentResolver().insert(collection, values);
        if (fileUri == null) {
            throw new IOException("Could not create CSV in Downloads");
        }

        OutputStream output = getContentResolver().openOutputStream(fileUri);
        if (output == null) {
            throw new IOException("Could not open CSV output stream");
        }

        try (OutputStreamWriter writer = new OutputStreamWriter(output, StandardCharsets.UTF_8)) {
            writer.write("Phone Number,Date,Duration (s),Scam Score,Is Scam,Threat Type,Risk Level,Blocked,Transcript Language,Transcript,Original Transcript\n");
            writer.write(escapeCsv(log.phoneNumber) + ",");
            writer.write(escapeCsv(formatDate(log.timestamp)) + ",");
            writer.write(log.callDurationSeconds + ",");
            writer.write(String.format(Locale.US, "%.2f", log.scamScore) + ",");
            writer.write((log.isScam ? "YES" : "NO") + ",");
            writer.write(escapeCsv(log.threatType != null ? log.threatType : "none") + ",");
            writer.write(escapeCsv(getRiskLabel(log.scamScore)) + ",");
            writer.write((log.blocked ? "YES" : "NO") + ",");
            writer.write(escapeCsv(language.translationName) + ",");
            writer.write(escapeCsv(exportTranscript) + ",");
            writer.write(escapeCsv(originalTranscript) + "\n");
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues done = new ContentValues();
            done.put(MediaStore.Downloads.IS_PENDING, 0);
            getContentResolver().update(fileUri, done, null, null);
        }

        return fileName;
    }

    private String translateTranscript(String transcript, LanguageOption language) throws Exception {
        String apiKey = getNvidiaLlmApiKey();
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalStateException("NVIDIA LLM key missing");
        }

        URL url = new URL(NVIDIA_ENDPOINT);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
        conn.setDoOutput(true);

        JsonArray messages = new JsonArray();
        JsonObject systemMessage = new JsonObject();
        systemMessage.addProperty("role", "system");
        systemMessage.addProperty("content",
                "Return strict JSON only. Preserve meaning, names, numbers, OTPs, dates, and scam-related terms.");
        messages.add(systemMessage);

        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        userMessage.addProperty("content",
                "Translate this call transcript to " + language.translationName + ". " +
                        "Return JSON only as {\"translation\":\"...\"}. Transcript: " + transcript);
        messages.add(userMessage);

        JsonObject body = new JsonObject();
        body.addProperty("model", NVIDIA_MODEL);
        body.add("messages", messages);
        body.addProperty("temperature", 0.1);
        body.addProperty("max_tokens", 1500);

        try (OutputStream os = conn.getOutputStream()) {
            byte[] input = gson.toJson(body).getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

        int code = conn.getResponseCode();
        String response = readHttpResponse(conn, code);
        conn.disconnect();

        if (code < 200 || code >= 300) {
            throw new IllegalStateException("NVIDIA translation failed: HTTP " + code);
        }

        String modelText = extractChatCompletionText(response);
        JsonObject translated = JsonParser.parseString(stripJsonFence(modelText)).getAsJsonObject();
        if (!translated.has("translation")) {
            throw new IllegalStateException("Translation response missing transcript");
        }
        return translated.get("translation").getAsString().trim();
    }

    private String readHttpResponse(HttpURLConnection conn, int code) throws IOException {
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
            throw new IllegalStateException("NVIDIA returned no translation");
        }
        JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
        if (message == null || !message.has("content")) {
            throw new IllegalStateException("NVIDIA returned empty translation");
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

    private boolean hasNvidiaAnalysisKey() {
        String key = getNvidiaLlmApiKey();
        return key != null && !key.trim().isEmpty();
    }

    private String getNvidiaLlmApiKey() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(PREF_NVIDIA_LLM_API_KEY, "");
    }

    private static class LanguageOption {
        final String label;
        final String code;
        final String translationName;
        final boolean requiresTranslation;

        LanguageOption(String label, String code, String translationName, boolean requiresTranslation) {
            this.label = label;
            this.code = code;
            this.translationName = translationName;
            this.requiresTranslation = requiresTranslation;
        }
    }

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

            Toast.makeText(this, "CSV exported to Downloads/CallTrace/" + fileName,
                    Toast.LENGTH_LONG).show();
        } catch (IOException e) {
            Toast.makeText(this, "Export failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
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
