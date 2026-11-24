/*
 * Copyright (c) 2026 The LME2510 Open Driver Authors.  MIT.
 *  Marker: lme2510-pbcnx.
 */
package com.pbcnx.drivers.lme2510;

/**
 * The frame encodings, transcribed from the vendor image and the captures.
 *
 * <p>A write is {@code 04 04 <dev> <reg hi> <reg lo> <val>}; a select is
 * {@code 04 03 <dev> <reg hi> <reg lo>}; a read is {@code 86 02 <dev|1> <count>
 * <reg lo>}. A write answers one byte ({@code 0x88}), a read answers {@code 0x55}
 * plus the payload.
 */
public final class Frames {

    public static final int TUNER = 0xB0;
    public static final int DEMOD = 0xBE;
    public static final int ACK = 0x88;
    public static final int REPLY = 0x55;

    private Frames() {
    }

    public static byte[] write(int register, int value, int device) {
        return new byte[]{0x04, 0x04, (byte) device,
                (byte) (register >>> 8), (byte) register, (byte) value};
    }

    public static byte[] select(int register, int device) {
        return new byte[]{0x04, 0x03, (byte) device,
                (byte) (register >>> 8), (byte) register};
    }

    public static byte[] read(int register, int count, int device) {
        return new byte[]{(byte) 0x86, 0x02, (byte) (device | 1),
                (byte) count, (byte) register};
    }

    /**
     * The filter frame the vendor sends before the stream starts:
     * {@code 03 | 2+4n | per PID {2i <- pid lo, 2i+1 <- pid hi} | 20 | 0x7f+2n}.
     * For {@code 0x1fff} that is the captured {@code 03 06 00 ff 01 1f 20 81}.
     */
    public static byte[] tsFilter(int... pids) {
        if (pids.length == 0 || pids.length > 30) {
            throw new IllegalArgumentException("a filter frame needs 1..30 PIDs");
        }
        int length = 2 + 4 * pids.length;
        byte[] frame = new byte[2 + length];
        frame[0] = 0x03;
        frame[1] = (byte) length;
        int at = 2;
        for (int index = 0; index < pids.length; index++) {
            frame[at++] = (byte) (2 * index);
            frame[at++] = (byte) (pids[index] & 0xFF);
            frame[at++] = (byte) (2 * index + 1);
            frame[at++] = (byte) ((pids[index] >>> 8) & 0xFF);
        }
        frame[at++] = 0x20;
        frame[at] = (byte) (0x7F + 2 * pids.length);
        return frame;
    }

    /** {@code 06 00} — the second frame that starts the TS. */
    public static byte[] streamStart() {
        return new byte[]{0x06, 0x00};
    }

    /** Lower-case hex SHA-256, for pinning firmware images and frame sequences. */
    public static String sha256(byte[] data) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder text = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                text.append(String.format("%02x", value & 0xFF));
            }
            return text.toString();
        } catch (java.security.NoSuchAlgorithmException absent) {
            throw new IllegalStateException("SHA-256 is required", absent);
        }
    }

    public static String hex(byte[] frame) {
        StringBuilder text = new StringBuilder(frame.length * 2);
        for (byte value : frame) {
            text.append(String.format("%02x", value & 0xFF));
        }
        return text.toString();
    }
}


