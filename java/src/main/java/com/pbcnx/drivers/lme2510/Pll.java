/*
 * Copyright (c) 2026 The LME2510 Open Driver Authors.  MIT.
 *  Marker: lme2510-pbcnx.
 */
package com.pbcnx.drivers.lme2510;

/**
 * The FC8300B synthesizer word, transcribed from the vendor image (the unnamed
 * helper at image offset 0x9e84).
 *
 * <p>The VCO runs at {@code 26 MHz * (q + t/65536)} and is divided by {@code m} to
 * reach the channel: {@code x = freq * m}, {@code q = x / 23000}-ish via the
 * reciprocal {@code 0xA151C331}, {@code t} from the remainder. The four registers
 * are written in the order 0x0f57, 0x0f5a, 0x0f58, 0x0f59 with 0x0f51 (the
 * prescaler code) first.
 *
 * <p>Checked against the Python module on the stations measured 2026-11-15:
 * 479143 kHz gives {@code 51=03 57=02 5a=dd 58=49 59=69}.
 */
public final class Pll {

    public static final long RECIPROCAL = 0xA151C331L;
    public static final int REFERENCE_KHZ = 26000;
    public static final int VCO_CEILING_KHZ = 6_000_000;
    public static final int[] PRESCALERS = {8, 12, 16, 24, 32, 48, 64, 96};

    public static final int REG_PRESCALER = 0x0F51;
    public static final int[] REG_WORD = {0x0F57, 0x0F5A, 0x0F58, 0x0F59};

    private Pll() {
    }

    /** {@code {multiplier, index}} chosen so {@code freq * m <= 6 GHz}. */
    public static int[] prescaler(int freqKHz) {
        for (int index = PRESCALERS.length - 1; index >= 0; index--) {
            if ((long) freqKHz * PRESCALERS[index] <= VCO_CEILING_KHZ) {
                return new int[]{PRESCALERS[index], index};
            }
        }
        return new int[]{PRESCALERS[0], 0};
    }

    /**
     * The five register values, in the order they are written: index 0 is 0x0f51,
     * then 0x0f57, 0x0f5a, 0x0f58, 0x0f59.
     */
    public static int[] word(int freqKHz) {
        int[] chosen = prescaler(freqKHz);
        int multiplier = chosen[0];
        int index = chosen[1];
        long x = (long) freqKHz * multiplier;
        long high = (x * RECIPROCAL) >>> 32;
        long q = high >>> 14;
        long remainder = x - (long) REFERENCE_KHZ * q;
        long fraction = (((remainder << 16) & 0xFFFFFFFFL) * RECIPROCAL) >>> 32;

        return new int[]{
                (2 * index + 1) & 0xFF,
                (int) (((high >>> 17) & 0x20) | (fraction >>> 26)) & 0xFF,
                (int) (q & 0xFF),
                (int) ((fraction >>> 18) & 0xFF),
                (int) (((fraction >>> 10) | 1) & 0xFF),
        };
    }
}
