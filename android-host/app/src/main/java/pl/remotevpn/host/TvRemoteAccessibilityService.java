package pl.remotevpn.host;

import android.accessibilityservice.AccessibilityService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * No root/ADB. The device owner must enable this service in Android Settings.
 * This is deliberately limited to TV navigation; it never reads window contents.
 */
public final class TvRemoteAccessibilityService extends AccessibilityService {
    private static volatile TvRemoteAccessibilityService current;

    static boolean isEnabled() { return current != null; }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        current = this;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }
    @Override public boolean onUnbind(android.content.Intent intent) {
        if (current == this) current = null;
        return super.onUnbind(intent);
    }
    @Override public void onDestroy() {
        if (current == this) current = null;
        super.onDestroy();
    }

    static String performTvKey(String key) {
        final TvRemoteAccessibilityService service = current;
        if (service == null) return "ERR ACCESSIBILITY_DISABLED";
        final int action;
        switch (key) {
            case "HOME": action = GLOBAL_ACTION_HOME; break;
            case "BACK": action = GLOBAL_ACTION_BACK; break;
            case "UP":
            case "DOWN":
            case "LEFT":
            case "RIGHT":
            case "OK":
                // Android 13 / API 33 introduced D-pad global actions.
                if (Build.VERSION.SDK_INT < 33) return "ERR UNSUPPORTED_ANDROID";
                switch (key) {
                    case "UP": action = GLOBAL_ACTION_DPAD_UP; break;
                    case "DOWN": action = GLOBAL_ACTION_DPAD_DOWN; break;
                    case "LEFT": action = GLOBAL_ACTION_DPAD_LEFT; break;
                    case "RIGHT": action = GLOBAL_ACTION_DPAD_RIGHT; break;
                    default: action = GLOBAL_ACTION_DPAD_CENTER; break;
                }
                break;
            default: return "ERR UNKNOWN_KEY";
        }
        // Run on service/main thread; transport calls this from its worker.
        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean ok = new AtomicBoolean(false);
        new Handler(Looper.getMainLooper()).post(() -> {
            if (current == service) ok.set(service.performGlobalAction(action));
            done.countDown();
        });
        try {
            if (!done.await(2, TimeUnit.SECONDS)) return "ERR ACTION_TIMEOUT";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "ERR ACTION_INTERRUPTED";
        }
        return ok.get() ? "OK KEY " + key : "ERR ACTION_FAILED";
    }
}
