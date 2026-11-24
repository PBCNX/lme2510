/*
 * Copyright (c) 2026 The LME2510 Open Driver Authors.  MIT.
 *  Marker: lme2510-pbcnx.
 */
package com.pbcnx.drivers.lme2510;

/**
 * TMCC as the LME2510C exposes it: the raw (184,102) codeword at {@code 0x4110}, LSB-first.
 *
 * <p>The demod decodes TMCC in order to demap the layers and hands the host the codeword: 23 bytes,
 * 184 bits, the shortened (184,102) block STD-B31 protects TMCC with. The alignment is fixed by the
 * hardware, not guessed: the only bit that moves when the EWBS flag changes is {@code 0x04} in
 * {@code 0x4113}, and with {@code 0x4110} as B0 read LSB-first that is exactly bit 26, where the
 * standard puts startup control.
 *
 * <p>Verified against two hand-set transmissions, an official EWBS broadcast, and the ISDB-T
 * Information Packet of a third-party 204-byte recording, which agreed field for field.
 *
 * <p><b>Mode and guard are not here.</b> STD-B31 carries them in the IIP (Tables 5-10/5-11), not in
 * TMCC, and this front end's captures carry no IIP. They are therefore {@code -1} — absent, never
 * guessed, so a host can hide the field instead of showing a wrong "8K".
 */
public final class Tmcc {

    /** First address of the codeword. */
    public static final int BASE = 0x4110;
    /** 23 bytes = 184 bits. */
    public static final int BYTES = 23;

    public static final int UNKNOWN = -1;
    public static final int UNUSED_LAYER = 15;

    /** One hierarchical layer's current parameters. */
    public static final class Layer {
        public final boolean present;
        public final int segments;
        /** 0 = DQPSK, 1 = QPSK, 2 = 16QAM, 3 = 64QAM, -1 = unknown. */
        public final int modulation;
        /** Index into 1/2, 2/3, 3/4, 5/6, 7/8, or -1. */
        public final int codeRate;
        /** 0 = I0, 1 = I1, 2 = I2, 3 = I4, or -1. */
        public final int interleaving;

        Layer(boolean present, int segments, int modulation, int codeRate, int interleaving) {
            this.present = present;
            this.segments = segments;
            this.modulation = modulation;
            this.codeRate = codeRate;
            this.interleaving = interleaving;
        }

        public String modulationName() {
            switch (modulation) {
                case 0: return "DQPSK";
                case 1: return "QPSK";
                case 2: return "16QAM";
                case 3: return "64QAM";
                default: return "?";
            }
        }

        public String codeRateName() {
            switch (codeRate) {
                case 0: return "1/2";
                case 1: return "2/3";
                case 2: return "3/4";
                case 3: return "5/6";
                case 4: return "7/8";
                default: return "?";
            }
        }

        public String interleavingName() {
            switch (interleaving) {
                case 0: return "I0";
                case 1: return "I1";
                case 2: return "I2";
                case 3: return "I4";
                default: return "?";
            }
        }
    }

    /** Transmission mode: -1 on this chip, because TMCC does not carry it. */
    public final int mode = UNKNOWN;
    /** Guard interval: -1 on this chip, because TMCC does not carry it. */
    public final int guard = UNKNOWN;
    public final boolean partial;
    public final boolean ews;
    public final Layer[] layers;
    /** The 23 bytes as read, for a host that wants to log or re-decode them. */
    public final byte[] raw;

    private Tmcc(boolean partial, boolean ews, Layer[] layers, byte[] raw) {
        this.partial = partial;
        this.ews = ews;
        this.layers = layers;
        this.raw = raw;
    }

    /** Read the codeword from a tuned, locked device. One shot: the parameters are fixed while tuned. */
    public static Tmcc read(Lme2510Device device) {
        byte[] block = new byte[BYTES];
        for (int offset = 0; offset < BYTES; offset++) {
            byte[] one = device.readRegister(BASE + offset, 1);
            if (one == null || one.length == 0) {
                // Refuse rather than fill with zeros: a zero-filled block decodes to a plausible-looking
                // DQPSK 1/2 reading, which is a fabricated verdict (the same defect the Python decoder
                // carried until 2026-11-20).
                throw new IllegalStateException(String.format(
                        "TMCC register 0x%04X did not answer", BASE + offset));
            }
            block[offset] = one[0];
        }
        return decode(block);
    }

    /** Decode the 23-byte codeword (LSB-first within each byte, {@code 0x4110} is B0). */
    public static Tmcc decode(byte[] block) {
        if (block == null || block.length < BYTES) {
            throw new IllegalArgumentException("TMCC block must be " + BYTES + " bytes");
        }
        boolean any = false;
        for (int index = 0; index < BYTES; index++) {
            if (block[index] != 0) {
                any = true;
                break;
            }
        }
        if (!any) {
            // An unlocked demod returns 23 zero bytes, and decoding those would print
            // "DQPSK 1/2 I0 0 seg" for all three layers - a reading nobody measured.
            throw new IllegalArgumentException("TMCC block is all zero: the demod was not locked");
        }
        int[] bits = new int[BYTES * 8];
        for (int index = 0; index < bits.length; index++) {
            bits[index] = (block[index / 8] >> (index % 8)) & 1;
        }
        boolean partial = field(bits, 27, 1) != 0;
        boolean ews = field(bits, 26, 1) != 0;
        Layer[] layers = new Layer[3];
        int base = 28;
        for (int index = 0; index < 3; index++) {
            int offset = base + 13 * index;
            int modulation = field(bits, offset, 3);
            int code = field(bits, offset + 3, 3);
            int interleaving = field(bits, offset + 6, 3);
            int segments = field(bits, offset + 9, 4);
            boolean present = modulation != UNUSED_LAYER && segments != UNUSED_LAYER;
            layers[index] = new Layer(present, segments,
                    present ? modulation : UNKNOWN,
                    present ? code : UNKNOWN,
                    present ? interleaving : UNKNOWN);
        }
        return new Tmcc(partial, ews, layers, block);
    }

    /**
     * How many segments the codeword claims, counting only layers that are present.
     *
     * <p>Mode and guard interval ride in the IIP, not in TMCC, so the segment count is the only thing in
     * this block that can corroborate a reading.</p>
     */
    public int segments() {
        int total = 0;
        for (Layer layer : layers) {
            if (layer != null && layer.present) {
                total += layer.segments;
            }
        }
        return total;
    }

    /**
     * A reading is a channel only when a layer claims segments (1..13).
     *
     * <p>IT9175's zero trap, measured 2026-11-20: a register the demod has not decoded yet reads 0, and
     * that decodes into a plausible-looking picture with no layers — indistinguishable, on a display, from
     * a real 2K / 1/32 multiplex. Refuse to present it rather than printing a mode-shaped line.</p>
     */
    public boolean valid() {
        int total = segments();
        return total >= 1 && total <= 13;
    }

    /**
     * Two agreeing **valid** readings, or {@code null} - the contract's {@code tmccSettled(attempts, delayMs)}
     * (TUNER-CONTRACT 1.2, §2 row 6): **the loop belongs to the package, never to the host.**
     *
     * <p>Measured 2026-11-20 on two other chips (IT9308 0.5 s, IT9175 800 ms): after a switch the codeword is
     * transient, and a stale block can pass {@link #valid()} while describing the previous multiplex. "Valid" is
     * not "settled", so one read is never enough to show a channel.</p>
     *
     * @return the settled reading, or {@code null} when the attempts ran out (the door renders that as absent)
     */
    public static Tmcc readSettled(Lme2510Device device, int attempts, long delayMs) {
        Tmcc previous = null;
        int tries = Math.max(1, attempts);
        for (int attempt = 0; attempt < tries; attempt++) {
            Tmcc current;
            try {
                current = read(device);
            } catch (RuntimeException notYet) {
                current = null;
            }
            if (current != null && current.valid() && previous != null
                    && current.describe().equals(previous.describe())) {
                return current;
            }
            previous = current;
            if (attempt + 1 < tries && delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return null;
    }

    /**
     * EWBS as the door's {@code int}: 1 announces, 0 does not, and **-1 means "this part cannot say"**.
     *
     * <p>A record only exists when the codeword decoded, so this returns 1 or 0; the "cannot say" case is the
     * **absence of a record** ({@code null}), which the adapter maps to -1 at the boundary (TUNER-CONTRACT 1.2).
     * The boolean inside the package stays, because a part that cannot read the bit must never render as
     * "no alert".</p>
     */
    public int ewbsValue() {
        return ews ? 1 : 0;
    }

    private static int field(int[] bits, int first, int length) {
        int value = 0;
        for (int position = first; position < first + length; position++) {
            value = (value << 1) | bits[position];
        }
        return value;
    }

    private static String layerText(String name, Layer layer) {
        if (!layer.present) {
            return "  " + name + ":-";
        }
        return "  " + name + ":" + layer.modulationName() + " " + layer.codeRateName() + " "
                + layer.interleavingName() + " " + layer.segments + "seg";
    }

    /** One line, in the shape {@code lme2510 tmcc} prints. */
    public String describe() {
        return "ewbs " + (ews ? 1 : 0) + "  partial " + (partial ? 1 : 0)
                + layerText("A", layers[0]) + layerText("B", layers[1]) + layerText("C", layers[2]);
    }

    @Override
    public String toString() {
        return describe();
    }
}
