package ru.drivedeck.launcher;

import java.util.HashMap;
import java.util.Map;

/**
 * he-16, pure (JVM-tested in test/CallTest.java): the Bluetooth calls the phone reports
 * (HFP client AG_CALL_CHANGED, BluetoothHeadsetClientCall id + state) and the call-state properties the
 * Topway firmware gates its steering-wheel keys on.
 */
final class CallBook {
    // BluetoothHeadsetClientCall states
    static final int ACTIVE = 0, HELD = 1, DIALING = 2, ALERTING = 3, INCOMING = 4, WAITING = 5,
            HELD_BY_RESPONSE_AND_HOLD = 6, TERMINATED = 7;

    private final Map<Integer, Integer> calls = new HashMap<Integer, Integer>();

    /** Records a call state. Returns true when this call is new (first seen, not already over). */
    synchronized boolean update(int id, int state) {
        if (state == TERMINATED) {
            calls.remove(id);
            return false;
        }
        boolean isNew = !calls.containsKey(id);
        calls.put(id, state);
        return isNew;
    }

    synchronized boolean idle() { return calls.isEmpty(); }

    synchronized void clear() { calls.clear(); }

    synchronized String describe() {
        if (calls.isEmpty()) return "none";
        StringBuilder b = new StringBuilder();
        for (Map.Entry<Integer, Integer> e : calls.entrySet()) {
            if (b.length() > 0) b.append(',');
            b.append(e.getKey()).append(':').append(e.getValue());
        }
        return b.toString();
    }

    /**
     * persist.dialer.call.state / persist.hicar.call.state survive a reboot. If a call ended without clearing one,
     * com.tw.service believes a call is still on and ignores the steering-wheel phone and voice keys.
     * Stuck = set to anything but 0 while no call is on.
     */
    static boolean isStuck(String value, boolean inCall) {
        if (inCall || value == null) return false;
        String v = value.trim();
        return !v.isEmpty() && !v.equals("0");
    }
}
