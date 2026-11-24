/*
 * Copyright (c) 2026 The LME2510 Open Driver Authors.  MIT.
 *  Marker: lme2510-pbcnx.
 */
package com.pbcnx.drivers.lme2510;

/**
 * The 57-record channel plan, transcribed from the vendor image (the band-4
 * handler's table lookup at +3000 kHz). Each record is {@code freq, byteA,
 * byteB}; {@code byteA} goes to 0x0fe9 and {@code byteB} to 0x0f52.
 *
 * <p>Generated from {@code lme2510/bandplan.py}; the Python test suite and this
 * table must agree record for record.
 */
public final class BandPlan {

    /** freq, byteA, byteB, repeating. */
    public static final int[] RECORDS = {// generated from lme2510/bandplan.py
        473143, 0x70, 0x40,
        479143, 0x70, 0x00,
        485143, 0x70, 0x00,
        491143, 0x70, 0x00,
        497143, 0x70, 0x00,
        503143, 0x6F, 0x00,
        509143, 0x6F, 0x00,
        515143, 0x6F, 0x00,
        521143, 0x6F, 0x40,
        527143, 0x6F, 0x00,
        533143, 0x6F, 0x00,
        539143, 0x6F, 0x00,
        545143, 0x6F, 0x70,
        551143, 0x6F, 0x30,
        557143, 0x6F, 0x00,
        563143, 0x70, 0x00,
        569143, 0x70, 0x30,
        575143, 0x70, 0x30,
        581143, 0x70, 0x00,
        587143, 0x71, 0x00,
        593143, 0x72, 0x30,
        599143, 0x72, 0x30,
        605143, 0x72, 0x30,
        611143, 0x73, 0x30,
        617143, 0x73, 0x00,
        623143, 0x74, 0x20,
        629143, 0x74, 0x30,
        635143, 0x73, 0x00,
        641143, 0x74, 0x00,
        647143, 0x74, 0x30,
        653143, 0x74, 0x50,
        659143, 0x73, 0x00,
        665143, 0x74, 0x00,
        671143, 0x74, 0x70,
        677143, 0x74, 0x40,
        683143, 0x73, 0x00,
        689143, 0x73, 0x00,
        695143, 0x73, 0x00,
        701143, 0x73, 0x40,
        707143, 0x72, 0x50,
        713143, 0x71, 0x50,
        719143, 0x71, 0x50,
        725143, 0x71, 0x50,
        731143, 0x70, 0x20,
        737143, 0x70, 0x50,
        743143, 0x70, 0x50,
        749143, 0x6F, 0x40,
        755143, 0x6F, 0x30,
        761143, 0x6F, 0x70,
        767143, 0x6F, 0x20,
        773143, 0x6E, 0x20,
        779143, 0x6E, 0x10,
        785143, 0x6E, 0x40,
        791143, 0x6E, 0x70,
        797143, 0x6D, 0x20,
        803143, 0x6D, 0x30,
        809143, 0x6C, 0x50,    };

    public static final int RECORD_SIZE = 3;
    public static final int TOLERANCE_KHZ = 3000;

    private BandPlan() {
    }

    /** {@code {byteA, byteB}} for the channel nearest {@code freqKHz}, or null. */
    public static int[] plan(int freqKHz) {
        for (int index = 0; index < RECORDS.length; index += RECORD_SIZE) {
            int centre = RECORDS[index];
            if (Math.abs(freqKHz - centre) <= TOLERANCE_KHZ) {
                return new int[]{RECORDS[index + 1], RECORDS[index + 2]};
            }
        }
        return null;
    }

    public static int records() {
        return RECORDS.length / RECORD_SIZE;
    }
}
