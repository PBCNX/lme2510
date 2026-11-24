/*
 * Copyright (c) 2026 The LME2510 Open Driver Authors.  MIT.
 *  Marker: lme2510-pbcnx.
 */
package com.pbcnx.drivers.lme2510;

import java.util.ArrayList;
import java.util.List;

/**
 * The 27-write set-frequency burst, transcribed from the vendor image
 * ({@code fc8300b_set_freq}) and checked bit for bit against the Python module.
 *
 * <p>Order matters: the 0x0013 shutdown, the demod's bandwidth and band-plan words,
 * the 0x0f1c IF value, then the synthesizer word with its 0x0f50 latch pairs, the
 * 0x0f77 conditional, the tail pair 0x0f78/0x0f04, and the closing 0x0001 triple.
 */
public final class Tune {

    public static final int BANDWIDTH_CODE = 0x16;      // 6 MHz
    public static final int BAND_SELECT = 0x0F1F;
    public static final int BAND_SELECT_VALUE = 0x79;
    public static final int IF_REGISTER = 0x0F1C;

    public static final int IF_MIN = 0x10;
    public static final int IF_MAX = 0x1F;
    public static final int SWEEP_OFFSET = 7;

    /** The vendor's chain of comparisons, edges in kHz. */
    public static final int[] GROUP_EDGES = {469488, 485000, 496999, 515000,
            533000, 581000, 605000, 635000, 671000, 737000, 803000};
    public static final int[] GROUP_VALUES = {0x0B, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9};

    private Tune() {
    }

    public static int frequencyGroup(int freqKHz) {
        for (int index = 0; index < GROUP_EDGES.length; index++) {
            if (freqKHz < GROUP_EDGES[index]) {
                return GROUP_VALUES[index];
            }
        }
        return 10;
    }

    public static int ifBase(int group) {
        return Math.max(0, 11 - group);
    }

    /** {@code clamp(table[group] + (peak - 7), 0x10, 0x1f)}. */
    public static int ifValue(int freqKHz, int peak) {
        int value = ifBase(frequencyGroup(freqKHz)) + (peak - SWEEP_OFFSET);
        return Math.max(IF_MIN, Math.min(IF_MAX, value));
    }

    /** The 27 frames of a set-frequency, in wire order. */
    public static List<byte[]> burst(int freqKHz, int peak) {
        int[] plan = BandPlan.plan(freqKHz);
        if (plan == null) {
            throw new IllegalArgumentException("no channel plan record for "
                    + freqKHz + " kHz");
        }
        int[] word = Pll.word(freqKHz);
        List<byte[]> frames = new ArrayList<>(27);
        frames.add(Frames.write(0x0013, 0x00, Frames.TUNER));
        frames.add(Frames.write(0x3011, BANDWIDTH_CODE, Frames.DEMOD));
        frames.add(Frames.write(0x0FE9, plan[0], Frames.DEMOD));
        frames.add(Frames.write(0x0F52, plan[1], Frames.DEMOD));
        frames.add(Frames.write(BAND_SELECT, BAND_SELECT_VALUE, Frames.DEMOD));
        frames.add(Frames.write(IF_REGISTER, ifValue(freqKHz, peak), Frames.TUNER));
        frames.add(Frames.write(0x0F75, 0x31, Frames.TUNER));
        frames.add(Frames.write(0x0F77, 0x00, Frames.TUNER));
        frames.add(Frames.write(0x0F51, word[0], Frames.TUNER));
        frames.add(Frames.write(0x0F57, word[1], Frames.TUNER));
        frames.add(Frames.write(0x0F5A, word[2], Frames.TUNER));
        frames.add(Frames.write(0x0F50, 0xE3, Frames.TUNER));
        frames.add(Frames.write(0x0F50, 0xFF, Frames.TUNER));
        frames.add(Frames.write(0x0F58, word[3], Frames.TUNER));
        frames.add(Frames.write(0x0F59, word[4], Frames.TUNER));
        frames.add(Frames.write(0x0F77, 0x02, Frames.TUNER));
        frames.add(Frames.write(0x0F50, 0xE3, Frames.TUNER));
        frames.add(Frames.write(0x0F50, 0xFF, Frames.TUNER));
        frames.add(Frames.write(0x0F75, 0x31, Frames.DEMOD));
        frames.add(Frames.write(0x0F78, 0xF1, Frames.DEMOD));
        frames.add(Frames.write(0x0F04, 0x12, Frames.DEMOD));
        frames.add(Frames.write(0x0F78, 0x31, Frames.DEMOD));
        frames.add(Frames.write(0x0F04, 0x10, Frames.DEMOD));
        frames.add(Frames.write(0x0001, 0x7F, Frames.DEMOD));
        frames.add(Frames.write(0x0001, 0xCF, Frames.DEMOD));
        frames.add(Frames.write(0x0001, 0xFF, Frames.TUNER));
        frames.add(Frames.write(0x0013, 0x80, Frames.TUNER));
        return frames;
    }
}
