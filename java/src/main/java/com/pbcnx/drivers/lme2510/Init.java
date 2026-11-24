/*
 * Copyright (c) 2026 The LME2510 Open Driver Authors.  MIT.
 *  Marker: lme2510-pbcnx.
 */
package com.pbcnx.drivers.lme2510;

/**
 * The warm init, composed: the captured literal blocks around the transcribed
 * modules.
 *
 * <pre>
 *   0..84    bbm bring-up                    InitBlocks.PREFIX
 *  85..161   the 77-write script            InitScript
 * 162..175   twelve closing writes          InitBlocks.AFTER_SCRIPT
 * 176..201   the VCO calibration            Calibration
 * 202..213   the default tune at 545143     InitBlocks.DEFAULT_TUNE
 * 214..234   the tuner_init tail            InitBlocks.AFTER_DEFAULT_TUNE
 * 235..297   the 0f1c sweep                 Sweep
 * 298..329   post-sweep writes              InitBlocks.AFTER_SWEEP
 * </pre>
 *
 * <p>Nothing here replays a vendor call: the blocks are constants and the three
 * modules decide. The composition reproduces the captured 330 frames when the
 * device answers as it did on 2026-11-15 (see the Python test of the same name).
 */
public final class Init {

    public static final int DEFAULT_FREQ_KHZ = 545143;

    private Init() {
    }

    /** Sending frames and reading one-byte registers — the channel this needs. */
    public interface Channel extends Calibration.Channel {
    }

    /**
     * Run the init. Returns the sweep result: index 0 is the winning step, then
     * one level per step.
     */
    public static int[] run(Channel channel, int crystal) {
        for (byte[] frame : InitBlocks.frames(InitBlocks.PREFIX)) {
            channel.send(frame);
        }
        for (byte[] frame : InitScript.frames(crystal)) {
            channel.send(frame);
        }
        for (byte[] frame : InitBlocks.frames(InitBlocks.AFTER_SCRIPT)) {
            channel.send(frame);
        }
        if (!Calibration.run(channel, 0)) {
            throw new IllegalStateException(
                    "VCO calibration failed: the tuner would be uncalibrated");
        }
        for (byte[] frame : InitBlocks.frames(InitBlocks.DEFAULT_TUNE)) {
            channel.send(frame);
        }
        for (byte[] frame : InitBlocks.frames(InitBlocks.AFTER_DEFAULT_TUNE)) {
            channel.send(frame);
        }
        int[] sweep = Sweep.run(channel);
        for (byte[] frame : InitBlocks.frames(InitBlocks.AFTER_SWEEP)) {
            channel.send(frame);
        }
        return sweep;
    }

    public static int[] run(Channel channel) {
        return run(channel, InitScript.CRYSTAL_VALUE);
    }
}
