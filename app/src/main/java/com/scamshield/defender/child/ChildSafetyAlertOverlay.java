package com.scamshield.defender.child;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.telecom.TelecomManager;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import java.lang.reflect.Method;

public class ChildSafetyAlertOverlay {
    private static View overlayView;
    private static Ringtone ringtone;
    private static final Handler handler = new Handler(Looper.getMainLooper());

    private ChildSafetyAlertOverlay() {
    }

    public static void show(Context context, int contactsNotified, boolean allowEndCall) {
        Context appContext = context.getApplicationContext();
        handler.post(() -> showInternal(appContext, contactsNotified, allowEndCall));
    }

    public static void dismiss(Context context) {
        handler.post(() -> {
            stopSound();
            if (overlayView == null) return;
            try {
                WindowManager wm = (WindowManager) context.getApplicationContext()
                        .getSystemService(Context.WINDOW_SERVICE);
                if (wm != null) wm.removeView(overlayView);
            } catch (Exception ignored) {
            }
            overlayView = null;
        });
    }

    private static void showInternal(Context context, int contactsNotified, boolean allowEndCall) {
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) return;
        removeExistingOverlay(wm);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(36, 36, 36, 36);
        root.setBackgroundColor(Color.argb(232, 120, 0, 0));

        TextView icon = new TextView(context);
        icon.setText("!");
        icon.setTextColor(Color.WHITE);
        icon.setTextSize(72);
        icon.setTypeface(Typeface.DEFAULT_BOLD);
        icon.setGravity(Gravity.CENTER);
        root.addView(icon);

        TextView title = new TextView(context);
        title.setText("CHILD SAFETY ALERT");
        title.setTextColor(Color.WHITE);
        title.setTextSize(28);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView body = new TextView(context);
        body.setText("Scam call detected.\nA suspicious caller may be extracting information from a child.\n\nSMS sent to "
                + contactsNotified + " emergency contacts.");
        body.setTextColor(Color.WHITE);
        body.setTextSize(18);
        body.setGravity(Gravity.CENTER);
        body.setPadding(0, 24, 0, 24);
        root.addView(body);

        Button dismiss = new Button(context);
        dismiss.setText("DISMISS");
        root.addView(dismiss, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        if (allowEndCall) {
            Button endCall = new Button(context);
            endCall.setText("END CALL NOW");
            root.addView(endCall, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            endCall.setOnClickListener(v -> {
                endCall(context);
                dismiss(context);
            });
        }

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                android.graphics.PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.CENTER;

        overlayView = root;
        dismiss.setOnClickListener(v -> dismiss(context));
        try {
            wm.addView(root, params);
            pulse(icon);
            playAlarm(context);
            handler.postDelayed(() -> dismiss(context), 30_000L);
        } catch (Exception e) {
            overlayView = null;
        }
    }

    private static void removeExistingOverlay(WindowManager wm) {
        stopSound();
        if (overlayView == null) return;
        try {
            wm.removeView(overlayView);
        } catch (Exception ignored) {
        }
        overlayView = null;
    }

    private static void pulse(View view) {
        ObjectAnimator scaleX = ObjectAnimator.ofFloat(view, View.SCALE_X, 1f, 1.18f, 1f);
        scaleX.setRepeatCount(ObjectAnimator.INFINITE);
        scaleX.setDuration(900);
        scaleX.start();
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(view, View.SCALE_Y, 1f, 1.18f, 1f);
        scaleY.setRepeatCount(ObjectAnimator.INFINITE);
        scaleY.setDuration(900);
        scaleY.start();
    }

    private static void playAlarm(Context context) {
        try {
            AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (audio != null) {
                audio.setStreamVolume(AudioManager.STREAM_ALARM,
                        audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);
            }
            Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            ringtone = RingtoneManager.getRingtone(context, uri);
            if (ringtone != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) ringtone.setLooping(true);
                ringtone.play();
            }
        } catch (Exception ignored) {
        }
    }

    private static void stopSound() {
        try {
            if (ringtone != null && ringtone.isPlaying()) ringtone.stop();
        } catch (Exception ignored) {
        }
        ringtone = null;
    }

    public static boolean endCall(Context context) {
        try {
            TelecomManager telecom = (TelecomManager) context.getSystemService(Context.TELECOM_SERVICE);
            if (telecom != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    && ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS)
                    == PackageManager.PERMISSION_GRANTED) {
                return telecom.endCall();
            }
        } catch (Exception ignored) {
        }

        try {
            Object telephony = context.getSystemService("phone");
            Method getITelephony = telephony.getClass().getDeclaredMethod("getITelephony");
            getITelephony.setAccessible(true);
            Object iTelephony = getITelephony.invoke(telephony);
            Method endCall = iTelephony.getClass().getMethod("endCall");
            return Boolean.TRUE.equals(endCall.invoke(iTelephony));
        } catch (Exception ignored) {
            return false;
        }
    }
}
