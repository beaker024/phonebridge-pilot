package org.phonebridge.controller;

import android.app.Activity;
import android.os.*;
import android.provider.Settings;
import android.content.Intent;
import android.graphics.Color;
import android.view.*;
import android.widget.*;

/** All enable, approve, pause, blacklist and revoke controls are physical-only. */
public final class MainActivity extends Activity {
    private TextView status, pairing;
    private final Handler clock = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        public void run() {
            ControllerService service = ControllerService.instance;
            status.setText(service == null ? "Accessibility service is OFF" : service.localStatus());
            clock.postDelayed(this, 500);
        }
    };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().getDecorView().setFilterTouchesWhenObscured(true);
        ScrollView scroll = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(24, 80, 24, 32);
        box.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(box); setContentView(scroll);
        TextView info = new TextView(this);
        info.setText("PhoneBridge Pilot\nOnly PhoneBridge Sandbox can be controlled. "
            + "No accounts, browser, settings, passwords or purchases. "
            + "Control expires after 15 minutes. Never paste pairing keys into ChatGPT.");
        box.addView(info);
        status = new TextView(this); box.addView(status);
        addButton(box, "1. Open Accessibility settings", () ->
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        addButton(box, "2. Allow required notification indication", () ->
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1));
        addButton(box, "Enable control for 15 minutes", () -> {
            ControllerService s = ControllerService.instance;
            if (s != null) s.activate();
        });
        addButton(box, "STOP / revoke current session", () -> {
            ControllerService s = ControllerService.instance;
            if (s != null) s.stopLocal();
        });
        addButton(box, "Pause for manual work", () -> {
            ControllerService s = ControllerService.instance;
            if (s != null) s.pauseLocal();
        });
        addButton(box, "Resume — require a new observation", () -> {
            ControllerService s = ControllerService.instance;
            if (s != null) s.resumeLocal();
        });
        addButton(box, "Approve displayed connection code", () -> {
            ControllerService s = ControllerService.instance;
            if (s != null) s.approveLocal();
        });
        addButton(box, "Reject connection request", () -> {
            ControllerService s = ControllerService.instance;
            if (s != null) s.rejectLocal();
        });
        addButton(box, "Open harmless sandbox", () -> {
            Intent launch = getPackageManager().getLaunchIntentForPackage(ControllerService.SANDBOX);
            if (launch != null) startActivity(launch);
            else Toast.makeText(this, "Install Sandbox APK first", Toast.LENGTH_SHORT).show();
        });
        pairing = new TextView(this);
        pairing.setTextIsSelectable(true); box.addView(pairing);
        addButton(box, "Show local pairing key for Termux", () -> {
            pairing.setText("Local pairing key (private):\n" + ControllerService.secret(this));
            clock.postDelayed(() -> pairing.setText(""), 60000);
        });
        EditText blacklist = new EditText(this);
        blacklist.setHint("Local blacklist: package IDs separated by commas");
        blacklist.setText(getSharedPreferences("private", 0).getString("blacklist", ""));
        box.addView(blacklist);
        addButton(box, "Save blacklist and STOP", () -> {
            getSharedPreferences("private", 0).edit().putString("blacklist", blacklist.getText().toString()).apply();
            ControllerService s = ControllerService.instance;
            if (s != null) s.stopLocal();
        });
        addButton(box, "Read local audit (no screen/text contents)", () -> {
            ControllerService s = ControllerService.instance;
            pairing.setText(s == null ? "Service unavailable" : s.auditText());
        });
        addButton(box, "Revoke pairing key and STOP", () -> {
            ControllerService s = ControllerService.instance;
            if (s != null) s.stopLocal();
            getSharedPreferences("private", 0).edit().remove("secret").commit();
            pairing.setText("Pairing revoked. Toggle Accessibility service off/on, then re-pair Termux.");
            if (s != null) s.closeIpc();
        });
    }
    private void addButton(LinearLayout box, String label, Runnable action) {
        Button b = new Button(this); b.setText(label);
        b.setFilterTouchesWhenObscured(true);
        b.setAccessibilityDataSensitive(true);
        b.setOnTouchListener((v, event) -> (event.getFlags() &
            (MotionEvent.FLAG_WINDOW_IS_OBSCURED | MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED)) != 0);
        b.setOnClickListener(v -> action.run()); box.addView(b);
    }
    @Override public void onResume() { super.onResume(); clock.post(refresh); }
    @Override public void onPause() { clock.removeCallbacks(refresh); super.onPause(); }
    @Override public void onDestroy() { clock.removeCallbacksAndMessages(null); super.onDestroy(); }
}
