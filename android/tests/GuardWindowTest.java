package com.codex.l06volume;

public final class GuardWindowTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        GuardWindow guard = new GuardWindow();
        check(!guard.shouldApply(0), "No correction before L06 becomes media output");
        check(!guard.updateRoute(false, 2000), "Waiting for route must not start correction");
        check(guard.updateRoute(true, 8000), "Late output and already-connected startup must begin correction");
        check(guard.shouldApply(8000), "New route must correct immediately");
        check(!guard.updateRoute(true, 18000), "Duplicate route hints must not restart correction");
        check(guard.shouldApply(22999), "Late Bluetooth restore must still be corrected");
        check(!guard.shouldApply(23000), "Window must end even after duplicate hints");
        check(!guard.updateRoute(true, 30000), "Manual changes on same route must not restart correction");
        check(!guard.shouldApply(30000), "Manual changes after window must be preserved");
        guard.updateRoute(false, 31000);
        check(!guard.shouldApply(31000), "Other output must cancel pending correction");
        check(guard.updateRoute(true, 32000), "A genuine reconnect must start a new window");
        guard.disconnect();
        check(!guard.shouldApply(32001), "Disconnect broadcast must cancel immediately");
        check(guard.updateRoute(true, 33000), "Rapid reconnect must get its own window");
        System.out.println("GuardWindow: 14 regression assertions passed");
    }
}
