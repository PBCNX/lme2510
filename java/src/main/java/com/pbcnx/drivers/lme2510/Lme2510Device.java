/*
 * Copyright (c) 2026 The LME2510 Open Driver Authors.  MIT.
 *  Marker: lme2510-pbcnx.
 */
package com.pbcnx.drivers.lme2510;

/**
 * The device object, portable across platforms.
 *
 * <p>It holds no USB code: the platform supplies a {@link Transport}. The wire
 * protocol lives in {@link Frames}, {@link BandPlan}, {@link Pll} and {@link Tune},
 * all transcribed from the vendor image; this class is the object a host calls.
 */
public final class Lme2510Device implements Init.Channel {

    public static final int VID = 0x3344;
    public static final int[] PIDS = {0x1120, 0x2480, 0x1111};
    public static final String SDK_VERSION = "LME SDK V1.6.isdbt";
    /** Visible attribution marker; not telemetry. See NOTICE. */
    public static final String PROVENANCE = "lme2510-pbcnx";

    /** The `0f1c` sweep peak minus 7 this unit stored (measured 2026-11-15). */
    public static final int DEFAULT_PEAK = 0x16;

    /**
     * Pause after each frame, in milliseconds: the vendor's own wait between writes
     * ({@code --frame-delay-ms 3.0} on the Python side, whose help text records ~4 ms).
     *
     * <p>The first Java port wrote frames back to back. That is invisible in the frame
     * bytes -- the byte-equality tests pass -- but it changes the hardware: the tune
     * burst reaches the PLL faster than it can latch, and the lock scan then watches
     * {@code 0x3025} climb from {@code 0x01} to {@code 0x3f} without the final lock bit
     * ({@code 0x40}) ever arriving inside its window. Measured on the LEAP: bit 6 never
     * came, while the same burst paced at 3 ms locks on the desktop.</p>
     */
    public static final int FRAME_DELAY_MS = 3;

    private final Transport transport;

    public Lme2510Device(Transport transport) {
        if (transport == null) {
            throw new IllegalArgumentException("transport");
        }
        this.transport = transport;
    }

    public void open() {
        transport.open();
    }

    public void close() {
        transport.close();
    }

    public boolean isOpen() {
        return transport.isOpen();
    }

    public String version() {
        return SDK_VERSION;
    }

    public static boolean accepts(int vendorId, int productId) {
        if (vendorId != VID) {
            return false;
        }
        for (int pid : PIDS) {
            if (pid == productId) {
                return true;
            }
        }
        return false;
    }

    /** One bulk write. */
    public int write(byte[] data) {
        return transport.write(data);
    }

    /** One bulk read; the caller aligns 188-byte packets. */
    public byte[] readBulk(int size) {
        return transport.read(size);
    }

    /**
     * Send the 27-frame set-frequency burst for {@code freqKHz}; returns how many
     * frames were accepted.
     */
    public int tune(int freqKHz) {
        return tune(freqKHz, DEFAULT_PEAK);
    }

    public int tune(int freqKHz, int peak) {
        int sent = 0;
        for (byte[] frame : Tune.burst(freqKHz, peak)) {
            transact(frame);          // every tune frame is answered too
            sent++;
        }
        return sent;
    }

    /** The alive register the vendor's tune conditional and the lock verdict both use. */
    public static final int ALIVE = 0x0F76;

    /**
     * Change channel on a live session — the vendor's own zap shape (2026-11-20).
     *
     * <p>Observed from {@code mydigital+TV 5.15}, the vendor application: the app's whole channel change is {@code setfrequency}
     * followed by {@code filtersetting(0, "8191")}, with the tune's own return as the lock verdict and the
     * read loop left running. That is this method: the 27-frame burst, the filter re-arm
     * ({@code 03 06 ... 81} + {@code 06 00}), and {@link #ALIVE} as the verdict. Nothing re-inits and the
     * caller's reader is not touched — a channel change is not a bring-up.</p>
     *
     * @return the {@code 0x0F76} byte, which is **not a lock verdict on this chip** - measured 2026-11-20:
     *         empty channels (479143, 683143) read {@code 0x1b} while the four-register scan refused, so
     *         "non-zero means locked" would call a dead channel locked. Non-zero only means *the device
     *         answered*. Judge the change by the stream (bytes after the re-arm) and scan when it stays
     *         silent - and note that a scan after a zap stops this pump, which is why the provider does not
     *         scan on the fast path.
     */
    public int zap(int frequencyKHz) {
        tune(frequencyKHz);
        // The verdict is read BEFORE the re-arm, which is the vendor's order: its setfrequency returns the
        // answer and only then does the app re-arm the filter.
        int word = readRegisterByte(ALIVE);
        transact(Frames.tsFilter(0x1FFF));
        transact(Frames.streamStart());
        return word;
    }

    /** One byte of a register, or -1 when the device did not answer. */
    public int readRegisterByte(int register) {
        byte[] data = readRegister(register, 1);
        return data.length > 0 ? data[0] & 0xFF : -1;
    }

    /** Tuner register 0xEB through the demod's 0x0F00 window: the vendor's RSSI. */
    public static final int RSSI = 0x0FEB;

    /** The gate that decides the vendor's +6 correction (fc8300b_get_rssi @ 0x1a1ec). */
    public static final int RSSI_GATE = 0x0F44;

    /** {@link #signalStrength()} when the device did not answer at all. */
    public static final int NO_SIGNAL = Integer.MIN_VALUE;

    /**
     * The tuner's received level, in the vendor's own units.
     *
     * <p>Transcribed from {@code fc8300b_get_rssi}: read tuner register {@code 0xEB} (one
     * signed byte), and add 6 when {@code 0x44 & 0x7F <= 0x5F}. The result is a
     * <b>relative</b> level, not dBm: measured on the LEAP 2026-11-16, a locked 8 Mbps mux
     * read -49 and an empty channel -57, which is too narrow a spread for dBm. Two frames
     * on the command endpoint, so it must not be called while the caller needs the pipe.</p>
     *
     * @return the level, or {@link #NO_SIGNAL} when the device answered nothing
     */
    public int signalStrength() {
        byte[] raw = readRegister(RSSI, 1);
        if (raw.length == 0) {
            return NO_SIGNAL;
        }
        int level = raw[0];
        byte[] gate = readRegister(RSSI_GATE, 1);
        if (gate.length > 0 && (gate[0] & 0x7F) <= 0x5F) {
            level += 6;
        }
        return level;
    }

    /**
     * {@link Init.Channel}: send one frame AND CONSUME ITS REPLY.
     *
     * <p>The device answers every frame on this path: the measured trace has 655
     * frames and 655 replies, 467 of them the single-byte {@code 0x88} ACK and 188
     * the {@code 0x55} payload. `device.py` reads that reply after every frame
     * (`exchange`); this class used to write and move on, so 330 warm-init ACKs and
     * the tune's went unread. The bridge then stopped answering: on the LEAP the
     * first calibration read got nothing at all (`ack=0 reply=0`), while the same
     * sequence worked on the desktop, whose transport always reads.</p>
     *
     * <p>A read frame ({@code 86 02 dev count reg}) is answered with
     * {@code 0x55 + count} bytes; every other frame with one byte.</p>
     */
    @Override
    public void send(byte[] frame) {
        transact(frame);
    }

    /** Writes a frame and returns its reply. */
    public synchronized byte[] transact(byte[] frame) {
        transport.write(frame);
        if (FRAME_DELAY_MS > 0) {
            try {
                Thread.sleep(FRAME_DELAY_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        int want = 1;
        if (frame.length == 5 && (frame[0] & 0xFF) == 0x86) {
            want = 1 + (frame[3] & 0xFF);
        }
        byte[] reply = transport.read(want);
        return reply == null ? new byte[0] : reply;
    }

    /** {@link Init.Channel}: one byte of a register. */
    @Override
    public int read(int register) {
        return readRegisterByte(register);
    }

    /**
     * Read {@code count} bytes of a register: select, then read, then the payload
     * after the {@code 0x55} marker (empty when the device answered something
     * else).
     */
    public synchronized byte[] readRegister(int register, int count) {
        return readRegister(register, count, Frames.TUNER);
    }

    /**
     * The same read on a named device byte. The captured tune writes part of the 0x0Fxx
     * window through {@link Frames#TUNER} (0xB0) and part through {@link Frames#DEMOD}
     * (0xBE), so a probe that must match the vendor's {@code bbm_bulk_read} has to be
     * able to say which one it means.
     */
    public synchronized byte[] readRegister(int register, int count, int device) {
        // SYNCHRONIZED AS A PAIR, not per frame: this method sends a select and then a read, and two frames
        // interleaved on one command endpoint produce one answer for two questions. The monitor is the same one
        // transact() takes (both are synchronized on this), so the pair is atomic and the inner calls re-enter.
        // HEAD, 2026-11-23: "lock the pair, not the frame".
        // both frames are answered: transact consumes the select ACK and the payload
        transact(Frames.select(register, device));
        byte[] reply = transact(Frames.read(register, count, device));
        if (reply != null && reply.length >= 1 + count && reply[0] == Frames.REPLY) {
            byte[] payload = new byte[count];
            System.arraycopy(reply, 1, payload, 0, count);
            return payload;
        }
        return new byte[0];
    }
}


