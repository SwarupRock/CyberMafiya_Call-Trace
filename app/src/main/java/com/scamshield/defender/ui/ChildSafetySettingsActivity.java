package com.scamshield.defender.ui;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.telephony.PhoneNumberUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.scamshield.defender.R;
import com.scamshield.defender.child.EmergencyContact;
import com.scamshield.defender.child.EmergencyContactManager;

import java.util.List;

public class ChildSafetySettingsActivity extends AppCompatActivity {
    private static final String PREFS_NAME = "scam_shield_prefs";
    public static final String PREF_CHILD_SAFETY_MODE = "child_safety_mode";
    public static final String PREF_CHILD_AUTO_DISCONNECT = "child_safety_auto_disconnect";
    private static final int REQ_SAFETY_PERMISSIONS = 801;

    private SharedPreferences prefs;
    private Switch modeSwitch;
    private Switch autoDisconnectSwitch;
    private LinearLayout contactsList;
    private TextView countBadge;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        setContentView(buildContent());
        refreshContacts();
    }

    private View buildContent() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(20));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("CHILD SAFETY");
        title.setTextColor(getColor(R.color.cyber_cyan));
        title.setTextSize(24);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title);

        TextView info = new TextView(this);
        info.setText("When enabled, Call Trace monitors calls for signs that a child is being targeted by a scammer and immediately alerts emergency contacts.");
        info.setTextColor(getColor(R.color.text_muted));
        info.setPadding(0, dp(14), 0, dp(14));
        root.addView(info);

        modeSwitch = new Switch(this);
        modeSwitch.setText("Child Safety Mode");
        modeSwitch.setTextColor(getColor(R.color.text_primary));
        modeSwitch.setChecked(prefs.getBoolean(PREF_CHILD_SAFETY_MODE, false));
        root.addView(modeSwitch);

        autoDisconnectSwitch = new Switch(this);
        autoDisconnectSwitch.setText("Auto-disconnect call when child alert fires");
        autoDisconnectSwitch.setTextColor(getColor(R.color.text_primary));
        autoDisconnectSwitch.setChecked(prefs.getBoolean(PREF_CHILD_AUTO_DISCONNECT, true));
        root.addView(autoDisconnectSwitch);

        countBadge = new TextView(this);
        countBadge.setTextColor(getColor(R.color.neon_amber));
        countBadge.setPadding(0, dp(18), 0, dp(8));
        root.addView(countBadge);

        contactsList = new LinearLayout(this);
        contactsList.setOrientation(LinearLayout.VERTICAL);
        root.addView(contactsList);

        Button addButton = new Button(this);
        addButton.setText("ADD EMERGENCY CONTACT");
        root.addView(addButton);

        modeSwitch.setOnCheckedChangeListener(this::onModeChanged);
        autoDisconnectSwitch.setOnCheckedChangeListener((button, checked) ->
                prefs.edit().putBoolean(PREF_CHILD_AUTO_DISCONNECT, checked).apply());
        addButton.setOnClickListener(v -> showAddContactDialog());
        return scroll;
    }

    private void onModeChanged(CompoundButton button, boolean enabled) {
        if (!enabled) {
            prefs.edit().putBoolean(PREF_CHILD_SAFETY_MODE, false).apply();
            return;
        }
        List<EmergencyContact> contacts = EmergencyContactManager.getCachedContacts(this);
        if (contacts.isEmpty()) {
            button.setChecked(false);
            new AlertDialog.Builder(this)
                    .setTitle("Emergency Contact Required")
                    .setMessage("Please add at least one emergency contact before enabling Child Safety Mode.")
                    .setPositiveButton("ADD CONTACT", (dialog, which) -> showAddContactDialog())
                    .setNegativeButton("CANCEL", null)
                    .show();
            return;
        }
        requestSafetyPermissions();
        prefs.edit()
                .putBoolean(PREF_CHILD_SAFETY_MODE, true)
                .putBoolean(PREF_CHILD_AUTO_DISCONNECT, true)
                .apply();
        autoDisconnectSwitch.setChecked(true);
    }

    private void requestSafetyPermissions() {
        boolean needsSms = ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
                != PackageManager.PERMISSION_GRANTED;
        boolean needsEndCall = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                && ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS)
                != PackageManager.PERMISSION_GRANTED;
        if (needsSms || needsEndCall) {
            String[] permissions = needsSms && needsEndCall
                    ? new String[]{Manifest.permission.SEND_SMS, Manifest.permission.ANSWER_PHONE_CALLS}
                    : new String[]{needsSms ? Manifest.permission.SEND_SMS : Manifest.permission.ANSWER_PHONE_CALLS};
            ActivityCompat.requestPermissions(this,
                    permissions, REQ_SAFETY_PERMISSIONS);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("Overlay Permission")
                    .setMessage("Allow Call Trace to show the child safety warning over active calls.")
                    .setPositiveButton("OPEN SETTINGS", (dialog, which) -> {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    })
                    .setNegativeButton("LATER", null)
                    .show();
        }
    }

    private void showAddContactDialog() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);
        form.setPadding(pad, pad, pad, pad);

        EditText name = new EditText(this);
        name.setHint("Name");
        form.addView(name);
        EditText phone = new EditText(this);
        phone.setHint("+919876543210");
        phone.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
        form.addView(phone);

        new AlertDialog.Builder(this)
                .setTitle("Add Emergency Contact")
                .setView(form)
                .setPositiveButton("ADD", (dialog, which) -> {
                    String cleanName = name.getText().toString().trim();
                    String cleanPhone = phone.getText().toString().trim();
                    if (cleanName.isEmpty() || !PhoneNumberUtils.isGlobalPhoneNumber(cleanPhone)) {
                        Toast.makeText(this, "Enter a valid name and global phone number.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    EmergencyContactManager.addEmergencyContact(this,
                            EmergencyContactManager.currentUid(), cleanName, cleanPhone);
                    refreshContacts();
                })
                .setNegativeButton("CANCEL", null)
                .show();
    }

    private void refreshContacts() {
        List<EmergencyContact> contacts = EmergencyContactManager.getEmergencyContacts(
                this, EmergencyContactManager.currentUid());
        renderContacts(contacts);
        EmergencyContactManager.getEmergencyContactsFromFirestore(
                this,
                EmergencyContactManager.currentUid(),
                freshContacts -> runOnUiThread(() -> renderContacts(freshContacts)));
    }

    private void renderContacts(List<EmergencyContact> contacts) {
        if (contacts == null) return;
        contactsList.removeAllViews();
        countBadge.setText(contacts.size() + " emergency contacts added");

        for (EmergencyContact contact : contacts) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(8), 0, dp(8));

            TextView label = new TextView(this);
            label.setText(contact.name + "  " + contact.maskedPhone());
            label.setTextColor(getColor(R.color.text_primary));
            row.addView(label, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1));

            Button delete = new Button(this);
            delete.setText("DELETE");
            row.addView(delete);
            delete.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setTitle("Delete Contact")
                    .setMessage("Remove " + contact.name + " from child safety alerts?")
                    .setPositiveButton("DELETE", (dialog, which) -> {
                        EmergencyContactManager.removeEmergencyContact(this,
                                EmergencyContactManager.currentUid(), contact.id);
                        refreshContacts();
                    })
                    .setNegativeButton("CANCEL", null)
                    .show());
            contactsList.addView(row);
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
