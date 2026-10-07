package org.phonebridge.controller;

/** Platform-independent part of the REAL native guard, tested on the local JVM. */
public final class GuardPolicy {
    public static final String SANDBOX = "org.phonebridge.sandbox";
    private GuardPolicy() { }
    public static boolean appBlocked(String packageName, String blacklist) {
        if (!SANDBOX.equals(packageName)) return true;
        for (String p : blacklist.split("[,\\s]+")) if (p.equals(packageName)) return true;
        return false;
    }
    public static void checkLease(String expected, String supplied, String observedDigest,
            String currentDigest, int observedWindow, int currentWindow,
            long observationAge, long quietAge) {
        if (expected.isEmpty() || !expected.equals(supplied) || !observedDigest.equals(currentDigest)
            || observedWindow != currentWindow || observationAge < 0 || observationAge > 90000
            || quietAge < 350) throw new SecurityException("stale-screen; observe again");
    }
    public static void checkText(String text) {
        if (text.length() > 512) throw new SecurityException("text-too-long");
        for (int i = 0; i < text.length(); i++)
            if (Character.isISOControl(text.charAt(i))) throw new SecurityException("control-text-denied");
    }
    public static void checkPoint(int left, int top, int right, int bottom, int barHeight, int x, int y) {
        if (x < left || x >= right || y < top || y >= bottom || y <= barHeight + 32)
            throw new SecurityException("unsafe-coordinate");
    }
}
