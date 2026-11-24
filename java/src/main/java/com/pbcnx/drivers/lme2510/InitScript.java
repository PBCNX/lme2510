/*
 * Copyright (c) 2026 The LME2510 Open Driver Authors.  MIT.
 *  Marker: lme2510-pbcnx.
 */
package com.pbcnx.drivers.lme2510;

import java.util.ArrayList;
import java.util.List;

/**
 * The tuner register script of the warm init ({@code FUN_0001884c}), transcribed.
 *
 * <p>77 single-byte writes to the demod, on the wire as {@code 0x0f00 | register}.
 * One value is not a constant: register 0x41 carries the crystal trim,
 * {@code round(cal/10)} — 0x3e on the captured unit. {@code 0x3f} is a latch
 * written 1 then 0.
 *
 * <p>Generated from {@code lme2510/init_script.py}; the Python test and this table
 * must agree frame for frame.
 */
public final class InitScript {

    public static final int PAGE = 0x0F00;
    public static final int CRYSTAL_REGISTER = 0x41;
    /** Marker for the computed value (the device has no such byte value). */
    public static final int CRYSTAL = -1;

    /** register, value, repeating. */
    public static final int[] SCRIPT = {            0, 0x00,
            2, 0x01,
            3, 0x04,
            4, 0x10,
            8, 0xE4,
            19, 0x07,
            21, 0x46,
            25, 0x53,
            29, 0x10,
            31, 0x79,
            32, 0x42,
            33, 0x30,
            36, 0x98,
            63, 0x01,
            65, CRYSTAL,
            63, 0x00,
            51, 0x86,
            52, 0x44,
            55, 0x65,
            56, 0x55,
            57, 0x05,
            60, 0xFF,
            61, 0xFF,
            62, 0xAB,
            77, 0x01,
            80, 0xFF,
            83, 0x1A,
            84, 0x80,
            112, 0x5F,
            120, 0x31,
            130, 0x88,
            132, 0x18,
            133, 0x0C,
            134, 0x3F,
            135, 0x18,
            136, 0x0E,
            137, 0x0B,
            138, 0x15,
            139, 0x0E,
            143, 0xB6,
            145, 0xF8,
            144, 0xB2,
            178, 0x00,
            179, 0x07,
            180, 0x00,
            181, 0x07,
            182, 0x00,
            183, 0x07,
            184, 0x00,
            185, 0x0A,
            186, 0x05,
            187, 0x0C,
            188, 0x05,
            189, 0x0C,
            190, 0x05,
            191, 0x0C,
            192, 0x0C,
            193, 0x05,
            194, 0x13,
            195, 0x05,
            211, 0x00,
            212, 0x01,
            213, 0x05,
            214, 0x0D,
            215, 0x0E,
            216, 0x0F,
            217, 0x2F,
            218, 0x4F,
            219, 0x6F,
            220, 0x77,
            221, 0x97,
            233, 0x79,
            237, 0x0C,
            239, 0xB7,
            205, 0x41,
            206, 0x88,
            207, 0x03,    };

    public static final int CRYSTAL_VALUE = 0x3E;

    private InitScript() {
    }

    /** The (register, value) pairs with the crystal trim filled in. */
    public static int[][] pairs(int crystal) {
        List<int[]> out = new ArrayList<>(SCRIPT.length / 2);
        for (int index = 0; index < SCRIPT.length; index += 2) {
            int value = SCRIPT[index + 1] == CRYSTAL ? crystal : SCRIPT[index + 1];
            out.add(new int[]{SCRIPT[index], value});
        }
        return out.toArray(new int[0][]);
    }

    /** The 77 write frames, as they appear on the wire. */
    public static List<byte[]> frames(int crystal) {
        List<byte[]> frames = new ArrayList<>();
        for (int[] pair : pairs(crystal)) {
            frames.add(Frames.write(PAGE | pair[0], pair[1], Frames.DEMOD));
        }
        return frames;
    }
}
