/*
 * Copyright (c) 2026 The LME2510 Open Driver Authors.  MIT.
 *  Marker: lme2510-pbcnx.
 */
package com.pbcnx.drivers.lme2510;

/**
 * The {@code 0f1c} signal sweep ({@code FUN_00019dbc}), transcribed.
 *
 * <p>Nine steps 0x13..0x1b; each writes 0x1c and averages three 0x0ff5 reads; the
 * best step wins and is returned with the levels. The sweep does not write the
 * peak back — the value a tune uses is {@code clamp(table + peak - 7)} later.
 *
 * <p>With no signal the levels are flat and the "best" is simply the first step,
 * which is why a stored level from a dark channel means nothing.
 */
public final class Sweep {

    public static final int TUNER = Frames.TUNER;
    public static final int PAGE = 0x0F00;
    public static final int REGISTER = 0x1C;
    public static final int MEASURE = 0x0FF5;
    public static final int FIRST_STEP = 0x13;
    public static final int LAST_STEP = 0x1B;
    public static final int READS_PER_STEP = 3;

    private Sweep() {
    }

    public static byte[] write(int register, int value) {
        return Frames.write(PAGE | register, value, TUNER);
    }

    /** ``{peak, levels...}`` — index 0 is the winning step, then one per step. */
    public static int[] run(Calibration.Channel channel) {
        int count = LAST_STEP - FIRST_STEP + 1;
        int[] out = new int[1 + count];
        int bestStep = -1;
        int bestLevel = -1;
        int slot = 1;
        for (int step = FIRST_STEP; step <= LAST_STEP; step++) {
            channel.send(write(REGISTER, step));
            int total = 0;
            for (int read = 0; read < READS_PER_STEP; read++) {
                int value = channel.read(MEASURE);
                total += Math.max(0, value);
            }
            int level = total / READS_PER_STEP;
            out[slot++] = level;
            if (level > bestLevel) {
                bestLevel = level;
                bestStep = step;
            }
        }
        out[0] = bestStep;
        return out;
    }
}
