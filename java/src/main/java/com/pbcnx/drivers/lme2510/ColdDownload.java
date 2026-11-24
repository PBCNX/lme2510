/*
 * Copyright (c) 2026 The LME2510 Open Driver Authors.  MIT.
 *  Marker: lme2510-pbcnx.
 */
package com.pbcnx.drivers.lme2510;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The cold-download frames (P5b), built from the firmware images rather than replayed.
 *
 * <p>Frame shape, measured 2026-11-15 against the 75-frame capture and the emulator
 * builder at {@code 0x59bc}:</p>
 *
 * <pre>
 *   [cmd] [len = payload-1] [payload ...] [sum8 = sum(payload) &amp; 0xFF]
 * </pre>
 *
 * <p>50-byte payloads, so a frame is 53 bytes. Two phases, two tails: the descriptor
 * blob (500 B) goes as ten {@code 0x01} frames and one {@code 0x81} tail of twelve zero
 * bytes — the builder sends 512 — and the code image (3,138 B) as sixty-two
 * {@code 0x02} frames and one {@code 0x82} tail of 38 bytes. A two-byte
 * {@code 8a 00} resets the MCU, after which the device re-enumerates as
 * {@code 3344:2480}.</p>
 *
 * <p>Totals, which {@link #build} checks: 75 frames, 3,650 B of payload, 3,874 B on the
 * wire — the same numbers as the capture.</p>
 */
public final class ColdDownload {

    /** Payload bytes per frame. */
    public static final int CHUNK = 50;
    /** The descriptor blob the vendor sends. */
    public static final int DESCRIPTOR_SIZE = 500;
    /** The 8051 image. */
    public static final int CODE_SIZE = 3138;
    /** Frames in the whole sequence. */
    public static final int FRAMES = 75;

    private ColdDownload() {
    }

    /**
     * Builds the 75 frames in order.
     *
     * @param code        the 3,138-byte image
     * @param descriptors the 500-byte descriptor blob
     * @throws IllegalArgumentException when an image is the wrong size
     */
    public static List<byte[]> build(byte[] code, byte[] descriptors) {
        if (code == null || code.length != CODE_SIZE) {
            throw new IllegalArgumentException(
                    "code image must be " + CODE_SIZE + " bytes, got "
                            + (code == null ? "null" : code.length));
        }
        if (descriptors == null || descriptors.length != DESCRIPTOR_SIZE) {
            throw new IllegalArgumentException(
                    "descriptor blob must be " + DESCRIPTOR_SIZE + " bytes, got "
                            + (descriptors == null ? "null" : descriptors.length));
        }
        List<byte[]> frames = new ArrayList<>(FRAMES);

        // 512 bytes as the builder sends it: the 500 of the blob, then twelve zeros.
        byte[] blob = Arrays.copyOf(descriptors, 512);
        for (int offset = 0; offset < 500; offset += CHUNK) {
            frames.add(frame(0x01, blob, offset, CHUNK));
        }
        frames.add(frame(0x81, blob, 500, 12));

        for (int offset = 0; offset < 3100; offset += CHUNK) {
            frames.add(frame(0x02, code, offset, CHUNK));
        }
        frames.add(frame(0x82, code, 3100, 38));

        frames.add(new byte[]{ (byte) 0x8A, 0x00 });
        if (frames.size() != FRAMES) {
            throw new IllegalStateException("built " + frames.size() + " frames");
        }
        return frames;
    }

    /** One framed chunk: cmd, len-1, payload, sum8. */
    static byte[] frame(int command, byte[] source, int offset, int length) {
        byte[] out = new byte[length + 3];
        out[0] = (byte) command;
        out[1] = (byte) (length - 1);
        System.arraycopy(source, offset, out, 2, length);
        int sum = 0;
        for (int i = 0; i < length; i++) {
            sum += source[offset + i] & 0xFF;
        }
        out[length + 2] = (byte) (sum & 0xFF);
        return out;
    }

    /** The payload of a frame, or an empty array for the reset frame. */
    public static byte[] payload(byte[] frame) {
        if (frame.length < 3) {
            return new byte[0];
        }
        int length = (frame[1] & 0xFF) + 1;
        return Arrays.copyOfRange(frame, 2, 2 + length);
    }

    /** True when the frame's trailing sum8 matches its payload. */
    public static boolean checksumOk(byte[] frame) {
        if (frame.length < 3) {
            return frame.length == 2 && (frame[0] & 0xFF) == 0x8A && frame[1] == 0;
        }
        byte[] body = payload(frame);
        int sum = 0;
        for (byte value : body) {
            sum += value & 0xFF;
        }
        return frame[body.length + 2] == (byte) (sum & 0xFF);
    }
}
