/*
 * Copyright (c) 2026 The LME2510 Open Driver Authors.  MIT.
 *  Marker: lme2510-pbcnx.
 */
package com.pbcnx.drivers.lme2510;

/**
 * The VCO band calibration ({@code FUN_00019558}), transcribed.
 *
 * <p>Four columns; each is tried in turn: write 0x71..0x74, stamp 0x5f=0x40,
 * 0x6d=0x0f, 0x6e=0x1f, 0x6f=0x3f, 0x6a=4, 0x6a=0x14, then 0x75=0x30 to start a
 * measurement, poll 0x76 for 0x20 (ten tries), read 0x76 after 0x75 = 0x34..0x37,
 * and if the first three are all non-zero stamp 0x5f=0 and stop. All four failing
 * leaves 0x70=0x7f.
 *
 * <p>A read that answers something unexpected is still a valid result: the four
 * sub-band reads only have to be non-zero. Demanding the capture's exact values is
 * what destroyed a calibration during the 2026-11-15 bring-up.
 */
public final class Calibration {

    public static final int TUNER = Frames.TUNER;
    public static final int PAGE = 0x0F00;
    public static final int READY = 0x20;
    public static final int[] SUB_BANDS = {0x34, 0x35, 0x36, 0x37};

    public static final int[][] COLUMNS = {
            {0xBB, 0xB3, 0x32, 0x0F},
            {0xBB, 0xBB, 0x43, 0x10},
            {0xBB, 0xBB, 0x54, 0x21},
            {0xBB, 0xBB, 0x65, 0x32},
    };

    /** Status byte -> how many columns to skip. */
    public static final int[] SELECTOR = {0x0F, 0x10, 0x21};

    public static final int FAIL_REGISTER = 0x70;
    public static final int FAIL_VALUE = 0x7F;
    public static final int SUCCESS_REGISTER = 0x5F;

    private Calibration() {
    }

    public static byte[] write(int register, int value) {
        return Frames.write(PAGE | register, value, TUNER);
    }

    /**
     * Run the search. {@code selector} 0 is the init; a tune passes the status byte
     * (0x0f/0x10/0x21 -> 1/2/3). Returns true when a column calibrated.
     */
    public static boolean run(Channel channel, int selector) {
        int start = 0;
        if (selector == 0) {
            start = 0;
        } else if (selector == 0x0F) {
            start = 1;
        } else if (selector == 0x10) {
            start = 2;
        } else if (selector == 0x21) {
            start = 3;
        } else if (selector > 3) {
            channel.send(write(FAIL_REGISTER, FAIL_VALUE));
            return false;
        }

        for (int column = start; column < COLUMNS.length; column++) {
            int[] values = COLUMNS[column];
            for (int index = 0; index < 4; index++) {
                channel.send(write(0x71 + index, values[index]));
            }
            channel.send(write(0x5F, 0x40));
            channel.send(write(0x6D, 0x0F));
            channel.send(write(0x6E, 0x1F));
            channel.send(write(0x6F, 0x3F));
            channel.send(write(0x6A, 0x04));
            channel.send(write(0x6A, 0x14));
            channel.send(write(0x75, 0x30));

            for (int attempt = 0; attempt < 10; attempt++) {
                if (channel.read(0x0F76) == READY) {
                    break;
                }
                sleep();
            }

            int[] results = new int[3];
            for (int index = 0; index < SUB_BANDS.length; index++) {
                channel.send(write(0x75, SUB_BANDS[index]));
                results[Math.min(index, 2)] = channel.read(0x0F76);
            }
            if (results[0] > 0 && results[1] > 0 && results[2] > 0) {
                channel.send(write(SUCCESS_REGISTER, 0x00));
                return true;
            }
        }
        if (start > 3) {
            channel.send(write(0x6A, 0x15));
        }
        channel.send(write(FAIL_REGISTER, FAIL_VALUE));
        return false;
    }

    public static void sleep() {
        try {
            Thread.sleep(1L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Sending frames and reading one-byte registers. */
    public interface Channel {
        void send(byte[] frame);

        int read(int register);
    }
}
