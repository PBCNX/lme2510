/*
 * Copyright (c) 2026 The LME2510 Open Driver Authors.  MIT.
* Marker: lme2510-pbcnx.
 */
package com.pbcnx.drivers.lme2510;

import android.content.Context;
import android.hardware.usb.UsbDevice;

import com.pbcnx.drivers.lme2510.android.AndroidTransport;
import com.pbcnx.tuner.Tuner;
import com.pbcnx.tuner.TunerException;
import com.pbcnx.tuner.TunerProvider;
import com.pbcnx.tuner.TsReader;

/**
 * The door's entry point for this package: {@code com.pbcnx.drivers:lme2510} exposes exactly this class, and a
 * host opens a tuner through it - never through a singleton or a hidden global (TUNER-CONTRACT 1.4, correction 1).
 *
 * <p>The firmware argument is the two images this part needs, **descriptors (500 B) followed by code (3,138 B)**,
 * because a tuner cannot be opened from a no-argument call and a cold stick needs both halves. A null image is
 * legal and only rules out the cold path: a warm {@code 3344:2480} opens without it.</p>
 */
public final class Lme2510Provider implements TunerProvider {

    /** 500-byte descriptor blob + 3,138-byte code image. */
    public static final int DESCRIPTOR_BYTES = 500;
    public static final int CODE_BYTES = 3138;

    /**
     * {@code recover()} closes the transport before it rebuilds, so the USB interface is released and a host may
     * grant a different node while the ladder runs. Measured on the LEAP 2026-11-22 (the host asked, and a host that
     * assumes otherwise skips a step it needs).
     */
    public static final boolean RECOVER_RELEASES_BUS = true;

    /**
     * {@code recover()} never uploads over a <b>warm</b> part. This stick's image lives in RAM: a replug or a
     * reset re-enumerates it as {@code 3344:1120}, so the P5b is legal only from cold, and an upload over a warm
     * chip is the shape SMSRIO measured wedging theirs three times for three replugs. {@code resume()} answers
     * {@code false} rather than doing it.
     */
    public static final boolean RECOVER_UPLOADS_ON_WARM_PART = false;

    /**
     * {@code status().strengthPercent} is a real frontend <b>level</b> (RSSI 0x0FEB, signed, gate-corrected, mapped
     * 0..100 between -70 and -35 dB) - but it is <b>not</b> a "there is a DTT multiplex here" test. Measured on the
     * 57-channel sweep 2026-11-20: channels with no station and no lock read up to 60, because an empty channel and a
     * quiet one both raise the level. A host that gates a recovery on "strength > 0" will spend its heal on dead muxes
     * (the host hit exactly this at 01:43 on 497143 and 599143 while the stations were off air).
     */
    public static final boolean STRENGTH_IS_A_PRESENCE_TEST = false;

/**
     * {@code start()} is a no-op for this package: {@code tune()} and {@code zap()} arm the pump themselves, so a host
     * that never calls {@code start()} still receives bytes (contract row 1b says the hook is optional, and a host
     * that omits it must not lose the stream). Published so a host can read the answer instead of testing for it.
     */
    public static final boolean NEEDS_START = false;

    /**
     * {@code recover()} does <b>not</b> re-enumerate the device, so a host does not need to suspend and re-grant
     * around it: the soft reset ({@code 8a 00}) keeps the same USB node - measured on the LEAP 2026-11-20 22:17,
     * where the node stayed {@code /dev/bus/usb/001/125} across the reset, the rebuild, the tune and the stream.
     * The hard reset ({@code 0a 00}) does re-enumerate and costs two permission dialogs; this package never sends
     * it.
     */
    public static boolean recoveryReenumerates() {
        return false;
    }

    private Lme2510Provider() {
    }

    @Override
    public Tuner open(Context context, UsbDevice device, byte[] firmware, TsReader.Listener listener) {
        if (context == null) {
            throw new IllegalArgumentException("LME2510: a Context is required");
        }
        byte[] descriptors = null;
        byte[] code = null;
        if (firmware != null && firmware.length > 0) {
            if (firmware.length != DESCRIPTOR_BYTES + CODE_BYTES) {
                throw new IllegalArgumentException("LME2510: firmware must be " + DESCRIPTOR_BYTES + " descriptor "
                        + "bytes followed by " + CODE_BYTES + " code bytes (got " + firmware.length + ")");
            }
            descriptors = new byte[DESCRIPTOR_BYTES];
            code = new byte[CODE_BYTES];
            System.arraycopy(firmware, 0, descriptors, 0, DESCRIPTOR_BYTES);
            System.arraycopy(firmware, DESCRIPTOR_BYTES, code, 0, CODE_BYTES);
        }
        UsbDevice target = device != null ? device : AndroidTransport.find(
                (android.hardware.usb.UsbManager) context.getSystemService(Context.USB_SERVICE));
        return new Lme2510Tuner(context, target, descriptors, code, listener);
    }

    /** The static factory the contract allows instead of the provider interface. */
    public static Tuner openTuner(Context context, UsbDevice device, byte[] firmware,
                                  TsReader.Listener listener) throws TunerException {
        return new Lme2510Provider().open(context, device, firmware, listener);
    }
}
