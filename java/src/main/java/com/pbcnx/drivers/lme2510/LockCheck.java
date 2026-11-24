/*
 * Copyright (c) 2026 The LME2510 Open Driver Authors.  MIT.
 *  Marker: lme2510-pbcnx.
 */
package com.pbcnx.drivers.lme2510;

/**
 * The vendor's lock scan ({@code fc8300_scan_status}), transcribed.
 *
 * <p>The machine is bit-based, not a byte compare: 0x3025 bit 0, then a poll for
 * bit 3, then bit 2, then 0x3026 bits 0+4, then 0x3025 bit 6, then 0x2023 bit 0
 * clear, then 0x3026 bit 1, and finally 0x50c5 non-zero ("the demod has data").
 * Bit 7 is never tested.
 *
 * <p>A lock is necessary, not sufficient: a scan can pass on noise (measured
 * 2026-11-15 at 485143, where it said "locked" and no TS arrived). The stream
 * remains the final verdict.
 */
public final class LockCheck {

    public static final int LOCK_REGISTER = 0x3025;
    public static final int STATUS_REGISTER = 0x3026;
    public static final int AUX_REGISTER = 0x2023;
    public static final int DATA_REGISTER = 0x50C5;

    /** bit0, bit3, bit2, bit6 — the order the vendor tests them. */
    public static final int[] LOCK_BITS = {0x01, 0x08, 0x04, 0x40};

    /**
     * The vendor's {@code msWait(10)} between polls (Python {@code lockcheck.WAIT}).
     *
     * <p>This was 1 ms in the first port, which is ten times too fast: the worst case
     * is 16 + 10 + 12 + 105 + 95 waits, so the scan gave up after ~240 ms where the
     * vendor takes ~2.4 s. A demod needs tens of milliseconds to assert these bits
     * after the set-frequency burst, so the scan reported "no lock" on a tuner that
     * was locking. Pinned by the self-test.</p>
     */
    public static final int POLL_WAIT_MS = 10;

    private LockCheck() {
    }

    /** A one-byte register reader; returns -1 when the device did not answer. */
    public interface Reader {
        int read(int register);
    }

    /** Poll {@code register} until {@code value & mask == expected}. */
    private static boolean poll(Reader reader, int register, int mask, int expected,
                               int tries) {
        for (int attempt = 0; attempt < tries; attempt++) {
            int value = reader.read(register);
            if (value >= 0 && (value & mask) == expected) {
                return true;
            }
            sleep();
        }
        return false;
    }

    private static void sleep() {
        try {
            Thread.sleep(POLL_WAIT_MS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** The scan; true means the vendor's chip-level check passed. */
    public static boolean scan(Reader reader) {
        return scan(reader, 4);
    }

    /** The scan, recording every read as {@code "0x3025=0x01"} for a host's log. */
    public static boolean scan(Reader reader, int band, java.util.List<String> trace) {
        if (trace == null) {
            return scan(reader, band);
        }
        Reader recording = register -> {
            int value = reader.read(register);
            trace.add(String.format("0x%04x=%s", register,
                    value < 0 ? "--" : String.format("0x%02x", value)));
            return value;
        };
        return scan(recording, band);
    }

    public static boolean scan(Reader reader, int band) {
        int value = reader.read(LOCK_REGISTER);
        if (value < 0) {
            return false;
        }
        if ((value & LOCK_BITS[0]) == 0) {
            boolean matched = false;
            for (int attempt = 0; attempt < 7; attempt++) {
                sleep();
                value = reader.read(LOCK_REGISTER);
                if (value >= 0 && (value & LOCK_BITS[0]) != 0) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                return false;
            }
        }
        if (!poll(reader, LOCK_REGISTER, LOCK_BITS[1], LOCK_BITS[1], 16)) {
            return false;
        }
        value = reader.read(LOCK_REGISTER);
        if (value < 0 || (value & LOCK_BITS[2]) == 0) {
            return false;
        }
        if (!poll(reader, STATUS_REGISTER, 0x11, 0x11, 10)) {
            return false;
        }
        if (!poll(reader, LOCK_REGISTER, LOCK_BITS[3], LOCK_BITS[3], 12)) {
            return false;
        }
        value = reader.read(AUX_REGISTER);
        if (value < 0 || (value & 0x01) != 0) {
            return false;
        }
        if (!poll(reader, STATUS_REGISTER, 0x02, 0x02, 105)) {
            return false;
        }
        // the per-band switch: every recovered arm shares "0x50c5 non-zero"
        return pollNonZero(reader);
    }

    /** 0x50c5 is a whole byte: the vendor tests it non-zero, not against a bit. */
    private static boolean pollNonZero(Reader reader) {
        for (int attempt = 0; attempt < 95; attempt++) {
            int value = reader.read(DATA_REGISTER);
            if (value > 0) {
                return true;
            }
            sleep();
        }
        return false;
    }
}
