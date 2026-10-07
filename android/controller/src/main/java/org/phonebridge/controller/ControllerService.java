package org.phonebridge.controller;

import android.accessibilityservice.*;
import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.util.Base64;
import android.view.*;
import android.view.accessibility.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Native security boundary. The bridge cannot activate or relax this policy. */
public final class ControllerService extends AccessibilityService {
    public static final String SANDBOX = GuardPolicy.SANDBOX;
    public static volatile ControllerService instance;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService encoder = Executors.newSingleThreadExecutor();
    private LocalHttpServer ipc;
    private LinearLayout indicator;
    private TextView indicatorText;
    private boolean enabled, paused;
    private long expires, epoch, lastChange, leaseTime, pendingTime;
    private String session = "", lease = "", leaseDigest = "", pending = "", approved = "";
    private int leaseWindow;
    private final SecureRandom random = new SecureRandom();
    private final Runnable expiry = new Runnable() {
        public void run() {
            if (enabled && SystemClock.elapsedRealtime() >= expires) stopLocal();
            if (!pending.isEmpty() && SystemClock.elapsedRealtime() - pendingTime > 120000) rejectLocal();
            main.postDelayed(this, 1000);
        }
    };
    static String secret(Context context) {
        android.content.SharedPreferences p = context.getSharedPreferences("private", 0);
        String s = p.getString("secret", null);
        if (s == null) {
            byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
            s = Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            p.edit().putString("secret", s).commit();
        }
        return s;
    }
    private String nonce() {
        byte[] b = new byte[24]; random.nextBytes(b);
        return Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }
    @Override public void onServiceConnected() {
        instance = this;
        stopLocal(); // Never resume after reboot/process restart/service re-enable.
        try { ipc = new LocalHttpServer(secret(this), this::request); }
        catch (IOException bad) { audit("ipc", "unavailable"); }
        main.post(expiry);
    }
    public void closeIpc() { if (ipc != null) { ipc.close(); ipc = null; } }
    @Override public void onInterrupt() { stopLocal(); }
    @Override public void onDestroy() {
        stopLocal(); closeIpc(); main.removeCallbacksAndMessages(null); instance = null;
        encoder.shutdownNow();
        super.onDestroy();
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        String p = String.valueOf(event.getPackageName());
        // Includes physical input events and all structural/text/scroll changes.
        // Do not access event text. Attribution to human vs agent is not reliable.
        if (SANDBOX.equals(p) || event.getEventType() == AccessibilityEvent.TYPE_WINDOWS_CHANGED
                || event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            epoch++; lease = ""; lastChange = SystemClock.elapsedRealtime();
        }
    }
    public void activate() {
        stopLocal();
        if (ipc == null) { audit("enable", "ipc-unavailable"); return; }
        if (!getSystemService(NotificationManager.class).areNotificationsEnabled()) {
            audit("enable", "notification-permission-required");
            Toast.makeText(this, "Allow notifications in app settings first", Toast.LENGTH_LONG).show(); return;
        }
        if (!showIndicator()) { audit("enable", "indicator-failed"); return; }
        session = nonce(); enabled = true; paused = false;
        expires = SystemClock.elapsedRealtime() + 900000;
        epoch++; lease = "";
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("active", "Phone control", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 1, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        nm.notify(1, new Notification.Builder(this, "active")
            .setSmallIcon(android.R.drawable.ic_lock_lock).setContentTitle("PhoneBridge control enabled")
            .setContentText("Sandbox only. Use the red STOP bar to revoke.")
            .setOngoing(true).setContentIntent(open).build());
        audit("enable", "local"); updateIndicator();
    }
    public void stopLocal() {
        enabled = false; paused = false; session = ""; lease = "";
        pending = ""; approved = ""; epoch++;
        if (indicator != null) {
            try { getSystemService(WindowManager.class).removeView(indicator); } catch (Exception ignored) { }
            indicator = null;
        }
        getSystemService(NotificationManager.class).cancel(1);
        audit("stop", "local-or-expiry");
    }
    public void pauseLocal() { paused = true; lease = ""; epoch++; audit("pause", "local"); updateIndicator(); }
    public void resumeLocal() { if (enabled) { paused = false; lease = ""; epoch++; audit("resume", "local"); updateIndicator(); } }
    public void approveLocal() {
        if (enabled && !pending.isEmpty() && SystemClock.elapsedRealtime() - pendingTime < 120000) {
            approved = pending; pending = ""; lease = ""; epoch++;
            audit("connection", "local-approved"); updateIndicator();
        }
    }
    public void rejectLocal() { pending = ""; approved = ""; lease = ""; epoch++; updateIndicator(); audit("connection", "rejected-or-expired"); }
    public String localStatus() {
        return !enabled ? "Remote control DISABLED" : (paused ? "PAUSED" : "Sandbox control ENABLED")
            + "\nExpires in " + Math.max(0, (expires - SystemClock.elapsedRealtime()) / 1000) + " seconds"
            + (pending.isEmpty() ? "" : "\nConnection code: " + pending.substring(0, 6));
    }
    private boolean showIndicator() {
        try {
            indicator = new LinearLayout(this);
            indicator.setOrientation(LinearLayout.HORIZONTAL);
            indicator.setBackgroundColor(Color.rgb(170, 0, 0));
            indicatorText = new TextView(this); indicatorText.setTextColor(Color.WHITE);
            indicator.addView(indicatorText, new LinearLayout.LayoutParams(0, -1, 1));
            Button pause = new Button(this); pause.setText("Pause");
            pause.setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES);
            pause.setOnClickListener(v -> { if (paused) resumeLocal(); else pauseLocal(); });
            indicator.addView(pause);
            Button stop = new Button(this); stop.setText("STOP"); stop.setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES);
            stop.setOnClickListener(v -> stopLocal()); indicator.addView(stop);
            WindowManager.LayoutParams p = new WindowManager.LayoutParams(-1, barHeight(),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
            p.gravity = Gravity.TOP | Gravity.LEFT;
            getSystemService(WindowManager.class).addView(indicator, p);
            return true;
        } catch (Exception failure) {
            if (indicator != null) {
                try { getSystemService(WindowManager.class).removeView(indicator); } catch (Exception ignored) { }
            }
            indicator = null; return false;
        }
    }
    private int barHeight() { return Math.round(60 * getResources().getDisplayMetrics().density); }
    private void updateIndicator() {
        if (indicator != null) indicatorText.setText(!pending.isEmpty() ? "Connection pending\nOpen controller"
            : paused ? "PAUSED\nManual control" : "PhoneBridge ON\nSandbox only");
    }
    private boolean audit(String command, String outcome) {
        // Never persist arguments, screen content, credentials, hashes of entered text.
        try {
            File f = new File(getFilesDir(), "audit.jsonl");
            if (f.length() > 262144) {
                File old = new File(getFilesDir(), "audit.previous.jsonl");
                if (old.exists()) old.delete(); f.renameTo(old);
            }
            try (FileOutputStream out = new FileOutputStream(f, true)) {
                JSONObject j = new JSONObject().put("time", System.currentTimeMillis())
                    .put("command", command).put("outcome", outcome);
                out.write((j.toString() + "\n").getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            return true;
        } catch (Exception ignored) { return false; }
    }
    public String auditText() {
        try { return new String(java.nio.file.Files.readAllBytes(new File(getFilesDir(), "audit.jsonl").toPath()), StandardCharsets.UTF_8); }
        catch (Exception e) { return "No audit log"; }
    }
    private void requireEnabled() {
        if (!enabled || SystemClock.elapsedRealtime() >= expires || indicator == null || !indicator.isAttachedToWindow())
            throw new SecurityException("disabled");
        KeyguardManager keyguard = getSystemService(KeyguardManager.class);
        if (keyguard.isKeyguardLocked() || !getSystemService(PowerManager.class).isInteractive()) {
            stopLocal(); throw new SecurityException("device-locked");
        }
    }
    private CompletableFuture<JSONObject> request(String path, JSONObject body) {
        CompletableFuture<JSONObject> result = new CompletableFuture<>();
        long deadline = SystemClock.elapsedRealtime() + 3500;
        main.post(() -> {
            String command = body.optString("command", "unknown");
            try {
                if (SystemClock.elapsedRealtime() > deadline) throw new SecurityException("expired-request");
                if ("/status".equals(path)) {
                    if (enabled && SystemClock.elapsedRealtime() >= expires) stopLocal();
                    result.complete(new JSONObject().put("enabled", enabled).put("session", session).put("paused", paused)); return;
                }
                requireEnabled();
                if ("/approval".equals(path)) {
                    String id = body.getString("request_id");
                    if (!id.matches("[a-f0-9]{48}")) throw new SecurityException("bad-approval-id");
                    if (!pending.isEmpty() || !approved.isEmpty()) throw new SecurityException("approval-busy");
                    pending = id; pendingTime = SystemClock.elapsedRealtime(); lease = ""; epoch++;
                    audit("connection", "requested"); updateIndicator();
                    result.complete(new JSONObject().put("pending", true)); return;
                }
                if ("/approval-status".equals(path)) {
                    String id = body.getString("request_id");
                    boolean yes = id.equals(approved);
                    if (yes) approved = ""; // one-time receipt
                    result.complete(new JSONObject().put("approved", yes).put("session", session)); return;
                }
                if (!"/command".equals(path)) throw new SecurityException("unknown-route");
                if (paused || !pending.isEmpty()) throw new SecurityException("manual-pause");
                if (!session.equals(body.getString("session"))) throw new SecurityException("wrong-session");
                if (!audit(command.matches("get_screen|tap|type_text|swipe|back|home|launch_app") ? command : "unknown", "requested"))
                    throw new SecurityException("audit-unavailable");
                if ("launch_app".equals(command)) {
                    if (!SANDBOX.equals(body.getString("package")) || blacklisted(SANDBOX)) throw new SecurityException("app-blocked");
                    // Only leave the known ChatGPT conversation or the sandbox.
                    // Do not redirect the user out of controller/permission/settings UI.
                    AccessibilityNodeInfo root = getRootInActiveWindow();
                    String current = root == null ? "" : String.valueOf(root.getPackageName());
                    if (!"com.openai.chatgpt".equals(current) && !SANDBOX.equals(current))
                        throw new SecurityException("launch-source-blocked; open sandbox manually");
                    Intent launch = getPackageManager().getLaunchIntentForPackage(SANDBOX);
                    if (launch == null) throw new SecurityException("sandbox-not-installed");
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(launch); done("launch_app", result); return;
                }
                if ("home".equals(command) || "back".equals(command)) {
                    // Deliberately deferred; these can enter settings/other activities.
                    throw new SecurityException("navigation-requires-manual-action-in-pilot");
                }
                Screen screen = screen();
                if ("get_screen".equals(command)) { observe(screen, body.optBoolean("screenshot", false), result); return; }
                consumeLease(body, screen);
                switch (command) {
                    case "tap": tap(body, screen, result); break;
                    case "type_text": type(body, screen, result); break;
                    case "swipe": swipe(body, screen, result); break;
                    default: throw new SecurityException("unknown-command");
                }
            } catch (Exception e) {
                // Only our fixed rejection messages escape; never exception argument values.
                String reason = e instanceof SecurityException ? e.getMessage() : "invalid-request";
                if (reason == null || !Arrays.asList(
                    "disabled", "device-locked", "expired-request", "bad-approval-id", "approval-busy",
                    "unknown-route", "manual-pause", "wrong-session", "audit-unavailable", "app-blocked",
                    "launch-source-blocked; open sandbox manually", "sandbox-not-installed",
                    "navigation-requires-manual-action-in-pilot", "unknown-command", "multiple-app-windows",
                    "system-window", "unknown-overlay", "tree-too-complex", "protected-screen", "foreign-node",
                    "manual-authentication-required", "screen-settling", "stale-screen; observe again",
                    "bad-element", "element-unavailable", "unsafe-coordinate", "coordinate-in-other-window",
                    "tap-failed", "not-practice-field", "text-too-long", "control-text-denied", "typing-failed",
                    "invalid-request").contains(reason)) reason = "policy-rejected";
                audit(command.matches("get_screen|tap|type_text|swipe|back|home|launch_app") ? command : "unknown", "rejected");
                try { result.complete(new JSONObject().put("error", reason)); }
                catch (JSONException ignored) { result.completeExceptionally(ignored); }
            }
        });
        return result;
    }
    private boolean blacklisted(String p) {
        return GuardPolicy.appBlocked(p, getSharedPreferences("private", 0).getString("blacklist", ""));
    }
    private static final class Screen {
        AccessibilityNodeInfo root;
        List<AccessibilityNodeInfo> refs = new ArrayList<>();
        JSONArray nodes = new JSONArray();
        Rect bounds = new Rect();
        int window;
        String digest;
    }
    private Screen screen() throws Exception {
        requireEnabled(); clearCache();
        Screen s = new Screen(); s.root = getRootInActiveWindow();
        if (s.root == null || blacklisted(String.valueOf(s.root.getPackageName()))) throw new SecurityException("app-blocked");
        s.root.getBoundsInScreen(s.bounds); s.window = s.root.getWindowId();
        for (AccessibilityWindowInfo w : getWindows()) {
            if (w.getType() == AccessibilityWindowInfo.TYPE_APPLICATION && w.getId() != s.window)
                throw new SecurityException("multiple-app-windows");
            if (w.getType() == AccessibilityWindowInfo.TYPE_SYSTEM && (w.isFocused() || w.isActive()))
                throw new SecurityException("system-window");
            if (w.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) {
                Rect r = new Rect(); w.getBoundsInScreen(r);
                if (r.bottom > barHeight() + 32) throw new SecurityException("unknown-overlay");
            }
        }
        collect(s.root, s, 0);
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        s.digest = Base64.encodeToString(md.digest((s.window + ":" + s.bounds + ":" + s.nodes).getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
        return s;
    }
    private void collect(AccessibilityNodeInfo node, Screen screen, int depth) throws Exception {
        if (node == null) return;
        if (depth > 40 || screen.refs.size() >= 400) throw new SecurityException("tree-too-complex");
        // Inspect protection flags before reading text/content descriptions.
        int input = node.getInputType();
        int variation = input & android.text.InputType.TYPE_MASK_VARIATION;
        if (node.isPassword() || node.isAccessibilityDataSensitive()
            || variation == android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            || variation == android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            || variation == android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            || ((input & android.text.InputType.TYPE_MASK_CLASS) == android.text.InputType.TYPE_CLASS_NUMBER
                && variation == android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD))
            throw new SecurityException("protected-screen");
        if (!SANDBOX.equals(String.valueOf(node.getPackageName()))) throw new SecurityException("foreign-node");
        String text = safe(node.getText()), description = safe(node.getContentDescription()), hint = safe(node.getHintText());
        String combined = (text + " " + description + " " + hint).toLowerCase(Locale.ROOT);
        if (combined.matches(".*(password|passcode|one.time.code|verification.code|captcha|authenticator|credit.card|security.code|2fa|two.factor).*"))
            throw new SecurityException("manual-authentication-required");
        Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
        JSONObject item = new JSONObject().put("id", "n" + screen.refs.size())
            .put("text", text).put("description", description).put("hint", hint)
            .put("resource_id", safe(node.getViewIdResourceName())).put("type", safe(node.getClassName()))
            .put("bounds", new JSONArray(new int[]{bounds.left, bounds.top, bounds.right, bounds.bottom}))
            .put("clickable", node.isClickable()).put("editable", node.isEditable())
            .put("scrollable", node.isScrollable()).put("focused", node.isFocused())
            .put("visible", node.isVisibleToUser()).put("enabled", node.isEnabled());
        screen.refs.add(node); screen.nodes.put(item);
        for (int i = 0; i < node.getChildCount(); i++) collect(node.getChild(i), screen, depth + 1);
    }
    private static String safe(CharSequence s) { return s == null ? "" : s.toString().substring(0, Math.min(s.length(), 512)); }
    private void observe(Screen screen, boolean image, CompletableFuture<JSONObject> result) throws Exception {
        if (SystemClock.elapsedRealtime() - lastChange < 350) throw new SecurityException("screen-settling");
        long observedEpoch = epoch;
        String observedSession = session;
        JSONObject response = new JSONObject().put("package", SANDBOX).put("nodes", screen.nodes)
            .put("bounds", new JSONArray(new int[]{screen.bounds.left, screen.bounds.top, screen.bounds.right, screen.bounds.bottom}));
        if (!image) { issueLease(screen, response); audit("get_screen", "completed"); result.complete(response); return; }
        takeScreenshotOfWindow(screen.window, getMainExecutor(), new TakeScreenshotCallback() {
            @Override public void onSuccess(ScreenshotResult shot) {
                if (!enabled || epoch != observedEpoch || !session.equals(observedSession)) {
                    shot.getHardwareBuffer().close(); completeError(result, "capture-rejected; observe again"); return;
                }
                // Never compress PNGs on the UI thread: physical STOP must stay responsive.
                try { encoder.execute(() -> {
                    Bitmap hardware = null, copy = null;
                    try {
                        hardware = Bitmap.wrapHardwareBuffer(shot.getHardwareBuffer(), shot.getColorSpace());
                        if (hardware == null) throw new IOException();
                        copy = hardware.copy(Bitmap.Config.ARGB_8888, false);
                        ByteArrayOutputStream png = new ByteArrayOutputStream();
                        copy.compress(Bitmap.CompressFormat.PNG, 100, png);
                        if (png.size() > 2000000) throw new IOException();
                        final String image = Base64.encodeToString(png.toByteArray(), Base64.NO_WRAP);
                        final int width = copy.getWidth(), height = copy.getHeight();
                        main.post(() -> {
                            try {
                                requireEnabled();
                                if (paused || !pending.isEmpty() || epoch != observedEpoch || !session.equals(observedSession)
                                    || !screen().digest.equals(screen.digest)) throw new SecurityException();
                                response.put("png_base64", image).put("image_width", width).put("image_height", height);
                                response.put("image_note", "App-window image only; use node bounds for screen coordinates.");
                                issueLease(screen, response); audit("get_screen", "completed"); result.complete(response);
                            } catch (Exception e) { completeError(result, "capture-rejected; observe again without screenshot"); }
                        });
                    } catch (Exception e) { completeError(result, "capture-rejected; observe again without screenshot"); }
                    finally { shot.getHardwareBuffer().close(); if (copy != null) copy.recycle(); if (hardware != null) hardware.recycle(); }
                }); } catch (java.util.concurrent.RejectedExecutionException gone) {
                    shot.getHardwareBuffer().close(); completeError(result, "disabled");
                }
            }
            @Override public void onFailure(int error) {
                // Never fall back to whole-display capture or override FLAG_SECURE.
                completeError(result, "capture-protected-or-unavailable; request metadata only");
            }
        });
    }
    private void issueLease(Screen s, JSONObject response) throws JSONException {
        lease = nonce(); leaseDigest = s.digest; leaseTime = SystemClock.elapsedRealtime(); leaseWindow = s.window;
        response.put("snapshot", lease).put("valid_for_seconds", 90).put("requires_fresh_observation_after_action", true);
    }
    private void consumeLease(JSONObject body, Screen s) throws Exception {
        String supplied = body.getString("snapshot");
        String expected = lease; lease = ""; // Single-use even if rejected.
        GuardPolicy.checkLease(expected, supplied, leaseDigest, s.digest, leaseWindow, s.window,
            SystemClock.elapsedRealtime() - leaseTime, SystemClock.elapsedRealtime() - lastChange);
    }
    private AccessibilityNodeInfo element(JSONObject b, Screen s) throws Exception {
        String id = b.getString("element_id");
        if (!id.matches("n[0-9]{1,3}")) throw new SecurityException("bad-element");
        int index = Integer.parseInt(id.substring(1));
        if (index >= s.refs.size()) throw new SecurityException("bad-element");
        AccessibilityNodeInfo n = s.refs.get(index);
        if (!n.isVisibleToUser() || !n.isEnabled()) throw new SecurityException("element-unavailable");
        return n;
    }
    private void point(Screen s, int x, int y) {
        GuardPolicy.checkPoint(s.bounds.left, s.bounds.top, s.bounds.right, s.bounds.bottom, barHeight(), x, y);
        for (AccessibilityWindowInfo w : getWindows()) {
            if (w.getId() == s.window) continue;
            Rect r = new Rect(); w.getBoundsInScreen(r);
            if (r.contains(x, y)) throw new SecurityException("coordinate-in-other-window");
        }
    }
    private void tap(JSONObject b, Screen s, CompletableFuture<JSONObject> result) throws Exception {
        if (b.has("element_id")) {
            AccessibilityNodeInfo n = element(b, s);
            Rect r = new Rect(); n.getBoundsInScreen(r); point(s, r.centerX(), r.centerY());
            if (!n.isClickable() || !n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) throw new SecurityException("tap-failed");
            done("tap", result); return;
        }
        int x = b.getInt("x"), y = b.getInt("y"); point(s, x, y);
        Path p = new Path(); p.moveTo(x, y); gesture(p, 80, "tap", result);
    }
    private void type(JSONObject b, Screen s, CompletableFuture<JSONObject> result) throws Exception {
        AccessibilityNodeInfo n = element(b, s);
        AccessibilityNodeInfo focus = s.root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focus == null || !n.equals(focus) || !n.isEditable() || !n.isFocused()
            || !(SANDBOX + ":id/practice_field").equals(n.getViewIdResourceName()))
            throw new SecurityException("not-practice-field");
        String text = b.getString("text");
        GuardPolicy.checkText(text);
        Bundle args = new Bundle(); args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        if (!n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) throw new SecurityException("typing-failed");
        done("type_text", result);
    }
    private void swipe(JSONObject b, Screen s, CompletableFuture<JSONObject> result) throws Exception {
        int x1 = b.getInt("x1"), y1 = b.getInt("y1"), x2 = b.getInt("x2"), y2 = b.getInt("y2");
        // Check whole stroke, including crossings through IME/overlays/system UI.
        for (int i = 0; i <= 100; i++) point(s, x1 + (x2 - x1) * i / 100, y1 + (y2 - y1) * i / 100);
        Path p = new Path(); p.moveTo(x1, y1); p.lineTo(x2, y2); gesture(p, 300, "swipe", result);
    }
    private void gesture(Path path, int duration, String command, CompletableFuture<JSONObject> result) {
        GestureDescription g = new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(path, 0, duration)).build();
        if (!dispatchGesture(g, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription ignored) { done(command, result); }
            @Override public void onCancelled(GestureDescription ignored) { completeError(result, "gesture-cancelled; observe again"); }
        }, main)) completeError(result, "gesture-rejected");
    }
    private void done(String command, CompletableFuture<JSONObject> result) {
        epoch++; lease = ""; lastChange = SystemClock.elapsedRealtime(); audit(command, "completed");
        try { result.complete(new JSONObject().put("completed", true).put("observe_again", true)); }
        catch (JSONException bad) { result.completeExceptionally(bad); }
    }
    private static void completeError(CompletableFuture<JSONObject> result, String reason) {
        try { result.complete(new JSONObject().put("error", reason)); }
        catch (JSONException bad) { result.completeExceptionally(bad); }
    }
}
