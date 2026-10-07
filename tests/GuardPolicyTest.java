import org.phonebridge.controller.GuardPolicy;

public class GuardPolicyTest {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("check " + checks); }
    private static void denied(Runnable action) {
        checks++;
        try { action.run(); throw new AssertionError("Expected native policy rejection: " + checks); }
        catch (SecurityException expected) { }
    }
    public static void main(String[] args) {
        check(!GuardPolicy.appBlocked(GuardPolicy.SANDBOX, ""));
        check(GuardPolicy.appBlocked(GuardPolicy.SANDBOX, "x,\n" + GuardPolicy.SANDBOX));
        check(GuardPolicy.appBlocked("com.android.settings", ""));
        check(GuardPolicy.appBlocked("org.phonebridge.controller", ""));
        check(GuardPolicy.appBlocked("com.lastpass.lpandroid", ""));
        check(GuardPolicy.appBlocked("com.android.chrome", ""));
        check(GuardPolicy.appBlocked("com.openai.chatgpt", ""));
        check(GuardPolicy.appBlocked("", ""));
        GuardPolicy.checkLease("nonce", "nonce", "d", "d", 1, 1, 1000, 500);
        denied(() -> GuardPolicy.checkLease("", "nonce", "d", "d", 1, 1, 1000, 500));
        denied(() -> GuardPolicy.checkLease("nonce", "wrong", "d", "d", 1, 1, 1000, 500));
        denied(() -> GuardPolicy.checkLease("nonce", "nonce", "d", "changed", 1, 1, 1000, 500));
        denied(() -> GuardPolicy.checkLease("nonce", "nonce", "d", "d", 1, 2, 1000, 500));
        denied(() -> GuardPolicy.checkLease("nonce", "nonce", "d", "d", 1, 1, 90001, 500));
        denied(() -> GuardPolicy.checkLease("nonce", "nonce", "d", "d", 1, 1, 1000, 349));
        denied(() -> GuardPolicy.checkLease("nonce", "nonce", "d", "d", 1, 1, -1, 500));
        GuardPolicy.checkText("hello phone — café");
        denied(() -> GuardPolicy.checkText("a\nb"));
        denied(() -> GuardPolicy.checkText("a\u0000b"));
        denied(() -> GuardPolicy.checkText("x".repeat(513)));
        GuardPolicy.checkPoint(0, 0, 1000, 2000, 120, 500, 700);
        denied(() -> GuardPolicy.checkPoint(0, 0, 1000, 2000, 120, 500, 50));
        denied(() -> GuardPolicy.checkPoint(0, 0, 1000, 2000, 120, 500, 152));
        denied(() -> GuardPolicy.checkPoint(0, 0, 1000, 2000, 120, -1, 700));
        denied(() -> GuardPolicy.checkPoint(0, 0, 1000, 2000, 120, 1000, 700));
        denied(() -> GuardPolicy.checkPoint(0, 0, 1000, 2000, 120, 500, 2000));
        System.out.println("Native GuardPolicy: " + checks + " security checks passed");
    }
}
