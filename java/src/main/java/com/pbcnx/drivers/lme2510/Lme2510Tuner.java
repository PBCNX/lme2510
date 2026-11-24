/*
 * Copyright (c) 2026 The LME2510 Open Driver Authors.  MIT.
* Marker: lme2510-pbcnx.
 */
package com.pbcnx.drivers.lme2510;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.util.Log;

import java.util.concurrent.atomic.AtomicLong;

import com.pbcnx.drivers.lme2510.android.AndroidTransport;
import com.pbcnx.tuner.ScanListener;
import com.pbcnx.tuner.Tmcc;
import com.pbcnx.tuner.Tuner;
import com.pbcnx.tuner.TunerException;
import com.pbcnx.tuner.TunerStatus;
import com.pbcnx.tuner.TsReader;

/**
 * The LME2510 behind the studio's tuner door (TUNER-CONTRACT 1.4, {@code com.pbcnx.tuner.Tuner}).
 *
 * <p>One instance per USB instance: the device, the firmware bytes and the listener arrive through
 * {@link Lme2510Provider#open}, and nothing is a singleton. Below this class is the same core the CLI and the
 * host app use, so a fix in {@link Lme2510Device}, {@link Tmcc} or {@link TsReader} reaches every host.</p>
 *
 * <p>Two calls are deliberately honest rather than clever: {@link #status()} reports snr as {@code -1} (this
 * front end has no snr read) and {@link #tmcc()} carries mode/guard as {@code -1} (they ride in the IIP and are
 * absent by design here - contract 4.8).</p>
 */
public final class Lme2510Tuner implements Tuner {

    private static final String TAG = "LME2510";
    private static final int CHUNK = 51200;
    private static final long SCAN_BYTES_MS = 1500L;
    /** Marginal muxes lock on the second burst - measured 2026-11-22 (479143: no lock, then LOCKED). */
    private static final int TUNE_ATTEMPTS = 2;

    private final Context context;
    private final byte[] descriptors;
    private final byte[] code;
    private final TsReader.Listener listener;
    private final AtomicLong bytes = new AtomicLong();
    private final TsQuality quality = new TsQuality();

    private UsbDevice device;
    private Transport transport;
    private Lme2510Device tuner;
    private Thread readerThread;
    private Thread scanThread;
    private volatile boolean running;
    /** The host has asked for the pump: start(), or a zap. A tune alone does not arm it. */
    private volatile boolean armed;
    private volatile boolean closed;
    private volatile int frequencyKhz;
    private volatile boolean locked;
    /** How many times this tuner has built a transport - each build is a fresh pipe, and each pipe's gaps restart. */
    private volatile int armCount;
    /** Why the current attach happened, printed with the arm, because every stall the host measured began at a re-arm. */
    private volatile String armReason = "open";

    /** When attach began, so every startup stage can be quoted against one clock (the host's launch stall). */
    private volatile long attachedAt;
    private volatile long initDoneAt;
    private volatile long firstByteAt;

    /** The last strength measured while the pump was NOT armed; reads are suppressed while streaming. */
    private volatile int lastStrength = -1;
    /**
     * The TMCC record as read while the pump was NOT armed, and the rule that keeps it valid.
     *
     * <p>This part's oldest measured rule: reading a register while the pump runs stops the stream (233 vs
     * 987 KiB/s on 2026-11-17, and one read per second costs 96 %). {@code status()} was given that guard first;
     * {@code tmcc()} was not, and a host that refreshes TMCC once a second - the door does - therefore stopped
     * the demod every second. The re-gate then restarted it, which is exactly the burst-and-stall rhythm seen on
     * 2026-11-23. While the pump is armed this returns the record captured at tune time; when it is not armed it
     * reads live and refreshes the cache.</p>
     */
    private volatile Object cachedTmcc;
    /** The live reader, so a pause can report whether its pool really left. */
    private volatile com.pbcnx.drivers.lme2510.TsReader reader;
    /** When the last tune was issued: the reference for first-byte latency. */
    private volatile long tunedAt;

    /** The live instance in this process, if any. */
    private static volatile Lme2510Tuner current;

    /** The instance this process currently holds open, or null. */
    public static Lme2510Tuner current() {
        return current;
    }
    Lme2510Tuner(Context context, UsbDevice device, byte[] descriptors, byte[] code,
                 TsReader.Listener listener) {
        this.context = context;
        this.device = device;
        this.descriptors = descriptors;
        this.code = code;
        this.listener = listener;
        // A USB device can be claimed once. If the host hands out a second Tuner without closing the first, the old
        // connection keeps the TS endpoint and the new one reads nothing while its register reads still work - the
        // exact shape of "locks, gate sent, bulk reads return -1" in a host that has been through several attempts.
        // Closing the previous instance here makes that leak impossible in this package rather than a rule to remember.
        Lme2510Tuner previous = current;
        current = this;
        if (previous != null && previous != this) {
            try {
                previous.close();
                Log.i(TAG, "a previous tuner was still open in this process and has been closed");
            } catch (RuntimeException ignored) {
                // closing a stale instance is best effort; the new one is what matters
            }
        }
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    public synchronized void open() throws TunerException {
        if (closed) {
            throw new TunerException("LME2510: this instance is closed");
        }
        try {
            if (device == null) {
                device = find(manager());
            }
            if (device == null) {
                throw new TunerException("LME2510: no device on the bus");
            }
            if (isCold(device)) {
                verifyDescriptors(true);          // the cold stick must answer 'D' before we upload anything
                bringUpCold();
            }
            attach(device);
        armReason = "open()";
            verifyDescriptors(false);             // and the warm one must answer 'G' after we attach
            // The reader is NOT started here. Measured on the LEAP 2026-11-22: a reader armed before any channel
            // exists delivers zero bytes for the whole window (bulk reads on an unarmed pipe), while the proven
            // path arms the pump first and reads after. The contract allows it either way (row 1b: a package
            // that cannot stream when open() returns makes start() the place, or starts on the first tune);
            // this part cannot stream without a tuned channel, so tune()/zap() arm it.
        } catch (TunerException already) {
            throw already;
        } catch (RuntimeException failure) {
            throw new TunerException("LME2510: open failed: " + failure, failure);
        }
    }

    @Override
    public synchronized boolean tune(int khz) {
        requireLive();
        frequencyKhz = khz;
        tunedAt = System.currentTimeMillis();
        locked = tuneAndScan(khz);
        // The one safe moment to read TMCC: the demod is locked and the pump is not armed yet. The host can then ask
        // as often as it likes (the contract promises that) without stopping its own stream.
        try {
            cachedTmcc = map(com.pbcnx.drivers.lme2510.Tmcc.read(tuner));
        } catch (RuntimeException ignored) {
            cachedTmcc = null;
        }
        try {
            int level = readLevel();
            lastStrength = level == Integer.MIN_VALUE ? -1 : percentOf(level);
        } catch (RuntimeException ignored) {
            lastStrength = -1;
        }
        // ALWAYS arm the pump. `start()` is OPTIONAL in the contract (row 1b) - a host calls it only when a package
        // declares it needs one - and the host's app never calls it, so the version that deferred arming to start()
        // locked the demod and handed the host ZERO bytes for twenty seconds (LEAP 2026-11-23 04:44). The reason
        // the arming had been deferred was the register-read-kills-the-stream finding, and that is now handled by
        // pausing the reader around TMCC reads instead of by withholding the pump.
        armed = true;
        rearm();
        startReader();
        return locked;
    }

    /**
     * A channel change on the live handle - the contract's order (tune, lock, re-arm) and our measured one. The
     * fast variant with no scan has no verdict on this part, so this returns the scan's, not a guess.
     */
    @Override
    public synchronized boolean zap(int khz) {
        requireLive();
        frequencyKhz = khz;
        tunedAt = System.currentTimeMillis();
        locked = tuneAndScan(khz);
        armed = true;
        rearm();
        startReader();
        return locked;
    }

    /**
     * Arm the pump (contract row 1b: a package that cannot stream when {@code open()} returns implements
     * {@code start()}). **This part needs it**, for a measured reason: a register read after the pump is armed
     * stops the stream dead - the door path on the LEAP 2026-11-22 read TMCC after {@code tune()} and got 0 bytes
     * for 8 s, while the same run with the read skipped streamed 4,659,200 B. The order a host should use is
     * open -> tune -> tmccSettled -> start, and {@link #tmcc()}/{@link #tmccSettled(int, long)} pause and re-arm
     * around their reads when the pump is already running.
     */
    @Override
    public synchronized void start() {
        if (tuner == null) {
            return;
        }
        armed = true;
        // NOT before a channel exists. Measured on the LEAP 2026-11-23: a reader armed before any tune delivered
        // ZERO bytes for the whole session while the direct path streamed 158 KiB/s on the same channel - the bulk
        // reads begin against an unarmed pipe and never recover. the host's app calls start() unconditionally after
        // open(), so the version that armed here guaranteed a silent session. With no frequency yet, start() only
        // records the host's request; the first tune() arms the pump and starts the reader.
        if (frequencyKhz > 0) {
            rearm();
            startReader();
        }
    }

    @Override
    public synchronized TunerStatus status() {
        if (tuner == null) {
            return new TunerStatus(false, -1, -1, -1);
        }
        // The conversion the Python side uses (lme2510/rssi.py): the register is a SIGNED byte, the demod's gate
        // adds a +6 dB correction when it is open, and the level maps to 0..100 between EMPTY and FULL. The first
        // version clamped the raw byte as if it were already a percent, which is why the host saw "signal present" on a
        // mux with no stations on it: the raw byte is not a percentage.
        int strength = -1;
        if (running || armed) {
            // THIS PART'S RULE, measured repeatedly: a register read while the pump is armed stops the stream. A host
            // that polls status() every second therefore starves its own video - and the vendor's own app shows the
            // difference: it polls two registers at ~14 Hz and still streams, because its reads go through a path that
            // re-arms, while ours would simply stop the pump. So while stream is live, report the level measured at
            // tune time instead of touching the device.
            strength = lastStrength;
        } else {
            try {
                int level = readLevel();
                strength = level == Integer.MIN_VALUE ? -1 : percentOf(level);
                lastStrength = strength;
            } catch (RuntimeException ignored) {
                strength = -1;
            }
        }
        int percent = -1;
        try {
            percent = (int) Math.round(quality.percent(System.nanoTime() / 1e9));
        } catch (RuntimeException ignored) {
            percent = -1;
        }
        return new TunerStatus(locked, strength, percent, -1);
    }

    @Override
    public synchronized Tmcc tmcc() {
        if (tuner == null) {
            return null;
        }
        if (running || armed) {
            // the pump is live: do NOT touch the device, and answer from what was captured while it was not
            return (Tmcc) cachedTmcc;
        }
        try {
            Tmcc reading = map(com.pbcnx.drivers.lme2510.Tmcc.read(tuner));
            cachedTmcc = reading;
            return reading;
        } catch (RuntimeException notNow) {
            return (Tmcc) cachedTmcc;
        }
    }

    @Override
    public Tmcc tmccSettled(int attempts, long delayMs) {
        Lme2510Device live;
        synchronized (this) {
            live = tuner;
        }
        if (live == null) {
            return null;
        }
        com.pbcnx.drivers.lme2510.Tmcc settled;
        boolean pause;
        boolean stopped = true;
        synchronized (this) {
            pause = running;
            if (pause) {
                stopped = pauseReader();
            }
        }
        try {
            settled = com.pbcnx.drivers.lme2510.Tmcc.readSettled(live, attempts, delayMs);
        } catch (RuntimeException notNow) {
            settled = null;
        } finally {
            synchronized (this) {
                if (pause && armed && stopped) {
                    rearm();
                    startReader();
                }
            }
        }
        return settled == null ? null : map(settled);
    }

    @Override
    public synchronized boolean recover() {
        if (closed) {
            return false;
        }
        // 1. the handle first (IT9175's ordering): close, wait, reopen, re-init, re-tune.
        if (rebuild() && tuneOnce(frequencyKhz)) {
            return true;
        }
        // 2. the chip: 8a 00 (LYDEV_ResetMCU), then the same rebuild.
        try {
            if (tuner != null) {
                tuner.write(new byte[]{(byte) 0x8A, 0x00});
            }
        } catch (RuntimeException ignored) {
            // the reset may race the detach; the rebuild is the test
        }
        closeQuietly();
        sleep(400L);
        if (rebuild() && tuneOnce(frequencyKhz)) {
            return true;
        }
        // 3. a cold or blank chip: the upload, then the rebuild.
        UsbDevice back = find(manager());
        if (back != null && isCold(back)) {
            try {
                device = back;
                bringUpCold();
            } catch (TunerException refused) {
                Log.w(TAG, refused.getMessage());
                return false;
            }
            return rebuild() && tuneOnce(frequencyKhz);
        }
        return false;
    }

    @Override
    public synchronized void suspend() {
        running = false;
        closeQuietly();
    }

    @Override
    public synchronized boolean resume(UsbDevice fresh) throws TunerException {
        if (closed) {
            throw new TunerException("LME2510: this instance is closed");
        }
        UsbDevice target = fresh != null ? fresh : find(manager());
        if (target == null) {
            return false;
        }
        try {
            device = target;
            if (isCold(target)) {
                bringUpCold();
                target = find(manager());
                if (target == null) {
                    return false;
                }
                device = target;
            }
            attach(target);
        armReason = "recover()";
            startReader();
            if (tuneOnce(frequencyKhz)) {
                return true;
            }
            // NOT a forced upload. IT9175's warm-path fix (2026-11-22) applies to a part whose firmware survives a
            // replug; SMSRIO measured the opposite and three forced uploads wedged their warm chip. Our part is on
            // SMSRIO's side: a replug re-enumerates as 3344:1120, i.e. the image is RAM-resident, so the upload is
            // only legal from a cold state - which is exactly what open()/bringUpCold() enforces. When a warm tune
            // fails to lock, the honest answer is false: the host's next step is a replug, not an upload.
            //
            // The check that would move this row is written down and unrun (docs\verification.md, "the upload
            // over a warm part"): warm the part, force the P5b, tune. Locking means the upload is survivable here;
            // no answer means it wedges, and this comment stays. Until then the ladder ends at the reset.
            return false;
        } catch (RuntimeException failure) {
            throw new TunerException("LME2510: resume failed: " + failure, failure);
        }
    }

    /**
     * Sweep the raster on a worker; **the stream is the verdict** (contract row 10), not the lock word - a full
     * sweep on 2026-11-22 passed 683143 at strength zero with no bytes behind it.
     */
    @Override
    public void scan(final int startKhz, final int stopKhz, final int stepKhz, final int seconds,
                     final ScanListener sink) {
        scanThread = new Thread(() -> {
            int found = 0;
            int total = 0;
            int step = Math.max(1000, stepKhz);
            for (int frequency = startKhz; frequency <= stopKhz; frequency += step) {
                if (closed || !running) {
                    break;
                }
                total++;
                boolean scanLocked = false;
                TunerStatus snapshot;
                synchronized (this) {
                    if (tuner == null) {
                        break;
                    }
                    try {
                        frequencyKhz = frequency;
                        tuner.tune(frequency);
                        scanLocked = LockCheck.scan(tuner::readRegisterByte);
                        locked = scanLocked;
                        if (scanLocked) {
                            armed = true;
                            rearm();
                        }
                    } catch (RuntimeException failure) {
                        break;
                    }
                    snapshot = status();
                }
                boolean station = scanLocked && waitForBytes(SCAN_BYTES_MS);
                if (station) {
                    found++;
                }
                if (sink != null) {
                    try {
                        sink.onFrequency(frequency, station, snapshot);
                    } catch (RuntimeException stop) {
                        break;
                    }
                }
            }
            if (sink != null) {
                try {
                    sink.onDone(found, total);
                } catch (RuntimeException ignored) {
                    // the host's callback threw; nothing left to report to
                }
            }
        }, "lme2510-scan");
        scanThread.start();
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (current == this) {
            current = null;
        }
        running = false;
        if (scanThread != null) {
            scanThread.interrupt();
            scanThread = null;
        }
        closeQuietly();
    }

    // ---------------------------------------------------------------- internals

    // ---------------------------------------------------------------- the descriptor gate

    /** GET_DESCRIPTOR, a string, out of the device into the host. */
    private static final int USB_DIR_IN = 0x80;
    private static final int USB_REQ_GET_DESCRIPTOR = 0x06;
    private static final int USB_DESCRIPTOR_STRING = 0x03;
    private static final int USB_LANG_EN = 0x0409;
    /** String 2 is the product string, and its third byte is the cold/warm marker ('D' cold, 'G' warm). */
    private static final int STRING_PRODUCT = 2;

    /**
     * The read-back gate (asked for by the host after a half-programmed part cost a long session): a stick whose descriptor
     * table did not land will **enumerate and then serve nothing**, so it opens happily and refuses every mux. The
     * strings cannot come from the code image (it holds one ASCII string and no table), so they are the only proof that
     * the firmware is serving the table it was sent.
     *
     * <p>Skipped when the transport cannot do control transfers (a test fake says so).</p>
     */
    private void verifyDescriptors(boolean cold) throws TunerException {
        if (transport == null || !transport.supportsControl()) {
            return;
        }
        byte[] buffer = new byte[255];
        int got;
        try {
            got = transport.control(USB_DIR_IN, USB_REQ_GET_DESCRIPTOR,
                    (USB_DESCRIPTOR_STRING << 8) | STRING_PRODUCT, USB_LANG_EN, buffer);
        } catch (RuntimeException refused) {
            throw new TunerException("LME2510: the descriptor gate could not be read (" + refused
                    + "); a stick whose strings do not answer cannot serve a channel", refused);
        }
        if (got < 4 || (buffer[0] & 0xFF) < 4) {
            throw new TunerException("LME2510: the descriptor gate read " + Math.max(got, 0)
                    + " bytes for string 2; a stick that cannot serve its product string cannot serve a channel");
        }
        checkMarker(buffer[2] & 0xFF, cold);
    }

    /** The marker rule, split out so it is testable without a device: 'D' cold, 'G' warm, nothing else. */
    static void checkMarker(int marker, boolean cold) throws TunerException {
        char got = (char) marker;
        char want = cold ? 'D' : 'G';
        if (got != want) {
            throw new TunerException("LME2510: descriptors do not match what the firmware should serve - string 2 "
                    + "reads '" + got + "', a " + (cold ? "cold" : "warm") + " part must read '" + want
                    + "'. The table did not land (or was overwritten); an upload is the repair, a tune is not.");
        }
    }

    /**
     * Passes every call through and logs the **first reads** of a session: the thread, the requested size and what
     * came back. the host asked three questions that this answers without a guess - which thread reads, what a zero-length
     * read does, and whether the read is even reaching the endpoint. The core stays free of Android logging.
     */
    private static final class LoggingTransport implements Transport {
        private static final int LOGGED_READS = 6;
        private final Transport inner;
        private static final int TS_READ_MIN = 4096;
        private final java.util.Map<String, java.util.concurrent.atomic.AtomicInteger> bigReads =
                new java.util.concurrent.ConcurrentHashMap<>();

        LoggingTransport(Transport inner) {
            this.inner = inner;
        }

        @Override
        public void open() {
            Log.i(TAG, "transport open on " + Thread.currentThread().getName());
            inner.open();
        }

        @Override
        public void close() {
            inner.close();
        }

        @Override
        public boolean orderedSource() {
            return inner.orderedSource();
        }

        @Override
        public long maxCompletionGapMs() {
            return inner.maxCompletionGapMs();
        }

        @Override
        public long windowMaxGapMs() {
            return inner.windowMaxGapMs();
        }

        @Override
        public int slowCompletions() {
            return inner.slowCompletions();
        }

        @Override
        public int write(byte[] data) {
            return inner.write(data);
        }

        @Override
        public byte[] read(int size) {
            byte[] data;
            try {
                data = inner.read(size);
            } catch (RuntimeException failed) {
                Log.i(TAG, "read(" + size + ") on " + Thread.currentThread().getName() + " threw " + failed);
                throw failed;
            }
            // TS reads only (the control path asks for 1-3 bytes), and a budget PER THREAD: a session-wide budget
            // was spent by the adopt path's control reads before any reader thread existed, which is exactly what
            // the host's 05:49 log showed.
            if (size > TS_READ_MIN) {
                int count = bigReads.computeIfAbsent(Thread.currentThread().getName(),
                        name -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();
                if (count <= LOGGED_READS) {
                    Log.i(TAG, "ts read #" + count + " on " + Thread.currentThread().getName() + ": asked " + size
                            + ", got " + (data == null ? "null" : data.length + " B"));
                }
            }
            return data;
        }

        @Override
        public int control(int requestType, int request, int value, int index, byte[] data) {
            return inner.control(requestType, request, value, index, data);
        }

        @Override
        public boolean supportsControl() {
            return inner.supportsControl();
        }

        @Override
        public boolean isOpen() {
            return inner.isOpen();
        }
    }

    private void requireLive() {
        if (closed || tuner == null) {
            throw new IllegalStateException("LME2510: no live session");
        }
    }

    /**
     * The burst plus the lock scan, **twice if the first does not lock**. A marginal multiplex locks on the
     * second burst and not the first - measured on hardware 2026-11-22, same channel, same minute: the CLI's
     * `LOCKED (after 2 tunes)` against a package `tune()` that sent one burst and answered false. The CLI and the
     * command line have always retried; the package shipping without it was the bug this hardware run found.
     */
    private boolean tuneAndScan(int khz) {
        for (int attempt = 1; attempt <= TUNE_ATTEMPTS; attempt++) {
            long started = System.currentTimeMillis();
            // What the burst will be computed FROM, printed before it is sent: a bad offset shows up as arithmetic
            // (the PLL word and the IF value) instead of as a mystery, which is what the host asked for on 599143.
            int[] word = null;
            int ifValue = -1;
            try {
                word = Pll.word(khz);
                ifValue = Tune.ifValue(khz, Lme2510Device.DEFAULT_PEAK);
            } catch (RuntimeException cannotCompute) {
                // a frequency outside the plan: the burst itself will say so
            }
            int answer = tuner.tune(khz);
            boolean locked = LockCheck.scan(tuner::readRegisterByte);
            long elapsed = System.currentTimeMillis() - started;
            // The four registers the lock machine actually keyed on, ONCE each - not the poll's retries of one
            // address. Measured on the hardware 2026-11-23 04:20: the recording wrapper recorded every retry of 0x3025,
            // so the line read "[0x3025=0x00 x8]" and 0x50c5 - the register the reading guide keys on - never
            // appeared. A trace that repeats one register cannot settle anything.
            Log.i(TAG, "tune " + khz + " kHz attempt " + attempt + "/" + TUNE_ATTEMPTS
                    + ": pll " + hex(word) + " if 0x" + Integer.toHexString(ifValue & 0xFF)
                    + ", 0x76 = 0x" + Integer.toHexString(answer)
                    + ", lock " + locked + " in " + elapsed + " ms"
                    + ", registers " + registers());
            if (locked) {
                return true;
            }
            if ((readOr(NO_CARRIER, LockCheck.LOCK_REGISTER) & 0xFF) == NO_CARRIER) {
                // no carrier at this frequency: stop, and say why in the words the next reader needs
                Log.i(TAG, "tune " + khz + " kHz: no carrier (0x" + Integer.toHexString(LockCheck.LOCK_REGISTER)
                        + "=0xff) after attempt " + attempt + " - NOT retrying, because a second burst cannot create a "
                        + "carrier; this is either the wrong frequency for this transmitter or the band is off");
                return false;
            }
        }
        return false;
    }

    /**
     * The value 0x3025 reads when there is NO CARRIER at the tuned frequency. Measured repeatedly on the hardware
     * (2026-11-23: "tune 497143 attempt 2/2: lock false in 2277 ms, registers 0x3025=0xff 0x3026=0x1f" on a band that
     * had gone dark). A second burst cannot create a carrier, so retrying on this value only multiplies the wait: on a
     * dead band the host measured an 18.5 s tune with six attempts, which is a product defect even when the stream is
     * perfect (IT9175, 2026-11-23). Retry is for a MARGINAL band, not an absent one.
     */
    private static final int NO_CARRIER = 0xFF;

    /** The four registers the lock machine reads, once each: 0x3025, 0x3026, 0x2023, 0x50c5. */
    private String registers() {
        return String.format("0x%04x=0x%02x 0x%04x=0x%02x 0x%04x=0x%02x 0x%04x=0x%02x",
                LockCheck.LOCK_REGISTER, readOr(0xFF, LockCheck.LOCK_REGISTER),
                LockCheck.STATUS_REGISTER, readOr(0xFF, LockCheck.STATUS_REGISTER),
                LockCheck.AUX_REGISTER, readOr(0xFF, LockCheck.AUX_REGISTER),
                LockCheck.DATA_REGISTER, readOr(0xFF, LockCheck.DATA_REGISTER));
    }

    private int readOr(int fallback, int register) {
        try {
            int value = tuner.readRegisterByte(register);
            return value < 0 ? fallback : value & 0xFF;
        } catch (RuntimeException refused) {
            return fallback;
        }
    }

    /** The PLL word as the four bytes the plan gives, or {@code "--"} when the frequency is outside it. */
    private static String hex(int[] word) {
        if (word == null) {
            return "--";
        }
        StringBuilder text = new StringBuilder();
        for (int value : word) {
            if (text.length() > 0) {
                text.append(' ');
            }
            text.append(String.format("%02x", value & 0xFF));
        }
        return text.toString();
    }

    private void rearm() {
        long started = System.currentTimeMillis();
        tuner.transact(Frames.tsFilter(0x1FFF));
        tuner.transact(Frames.streamStart());
        // IT9175's gate question (2026-11-23): the door's "re-arm the reader" is the HOST's business, while
        // "open the gate" is these two frames and only this package writes them. Printing them at the place they
        // are sent - with the lock state and the caller's thread - answers whether the gate is opened on the
        // warm-adopt path at all, which no host-side log can see.
        Log.i(TAG, "gate: tsFilter(0x1FFF) + streamStart(06 00) sent on "
                + Thread.currentThread().getName() + " at " + frequencyKhz + " kHz, locked " + locked
                + ", " + (System.currentTimeMillis() - started) + " ms");
    }

    private UsbManager manager() {
        return (UsbManager) context.getSystemService(Context.USB_SERVICE);
    }

    private UsbDevice find(UsbManager manager) {
        return manager == null ? null : AndroidTransport.find(manager);
    }

    private static boolean isCold(UsbDevice candidate) {
        return candidate != null && candidate.getProductId() == 0x1120;
    }

    private void attach(UsbDevice target) throws TunerException {
        attachedAt = System.currentTimeMillis();
        armCount++;
        Log.i(TAG, "arm " + armCount + " at " + attachedAt + ": " + armReason
                + " (each arm is a fresh queue, so the pipe's gap counters restart here)");
        transport = new LoggingTransport(new AndroidTransport(context, target));
        transport.open();
        tuner = new Lme2510Device(transport);
        tuner.open();
        // A UsbDevice object OUTLIVES the enumeration it came from (IT9175, 2026-11-23): openDevice() on a survivor can
        // succeed, the descriptors can be readable and the endpoint table can resolve, while the object is the node from
        // BEFORE a re-enumeration - which is the shape of every host row tonight (control path fine, bulk starved). So
        // check that the object we were handed is still the one the bus is enumerating, and refuse to work on a survivor.
        checkNotStale(target);
        // THE WARM INIT. Without it the demod is uncalibrated: a tune writes the PLL, the burst is accepted
        // (0x76 = 0x1b), and 0x3025 never sets a bit while every register reads zero - which is exactly the row
        // the host measured on 2026-11-23 04:37 (497143 live, the 9308 streaming it, our part refusing in ~220 ms with
        // all four registers 0x00). The CLI has always run these frames; this class did not, so
        // from the cold upload at 01:42 (which wiped the chip) nothing this package opened could lock.
        long initStarted = System.currentTimeMillis();
        int[] sweep = Init.run(tuner);
        initDoneAt = System.currentTimeMillis();
        Log.i(TAG, "startup: init " + sweep[0] + " frames in " + (initDoneAt - initStarted) + " ms, "
                + (initDoneAt - attachedAt) + " ms since attach began");
        int peak = 0;
        for (int value : sweep) {
            peak = Math.max(peak, value);
        }
        Log.i(TAG, "warm init: " + sweep.length + " channels swept, peak step 0x" + Integer.toHexString(peak));
        locked = false;
    }

    /** The cold stick: the two images (the P5b), then the warm instance that comes back. */
    private void bringUpCold() throws TunerException {
        if (descriptors == null || code == null) {
            throw new TunerException("LME2510: the device is cold (3344:1120) and no firmware image was supplied");
        }
        AndroidTransport loader = new AndroidTransport(context, device);
        try {
            loader.open();
            Lme2510Device cold = new Lme2510Device(loader);
            cold.open();
            for (byte[] frame : ColdDownload.build(code, descriptors)) {
                cold.transact(frame);
            }
        } catch (RuntimeException failure) {
            throw new TunerException("LME2510: cold bring-up failed: " + failure, failure);
        } finally {
            try {
                loader.close();
            } catch (RuntimeException ignored) {
                // a handle that just re-enumerated is allowed to refuse
            }
        }
        // WAIT for the re-enumeration. A fresh plug re-numbers the node (the host saw /001/055 -> /001/056) and the
        // device can take seconds to come back; a single look after 600 ms threw at the host's 05:49 cold start, which
        // a viewer would have seen as a failure followed by a second, working open.
        UsbDevice warm = null;
        for (int attempt = 1; attempt <= 25 && warm == null; attempt++) {
            sleep(200L);
            warm = find(manager());
            if (warm != null && isCold(warm)) {
                warm = null;                 // the loader is still enumerating; keep waiting
            }
            if (attempt % 5 == 0) {
                Log.i(TAG, "cold bring-up: waiting for the warm instance (" + attempt * 200 + " ms)");
            }
        }
        if (warm == null) {
            throw new TunerException("LME2510: the stick did not re-enumerate as 3344:2480 within 5 s of the P5b");
        }
        // The upload re-enumerates the stick on a NEW node (the host measured 001/082 -> 001/083 on the LEAP,
        // 2026-11-22 23:25), and the host's grant is for the node it passed. Opening the new node from inside
        // open() therefore fails with "User has not given ... permission" - which is what the host saw, and it is
        // this line's defect, not the host's. The device argument is the host's to grant; after a re-enumeration
        // the host must grant the new node and call resume(newDevice), which is what suspend()/resume() are for.
        if (!warm.getDeviceName().equals(device.getDeviceName())) {
            throw new TunerException("LME2510: the P5b re-enumerated the stick as 3344:2480 at "
                    + warm.getDeviceName() + " (was " + device.getDeviceName()
                    + "); the host must grant that node and call resume(device) - open() will not open a node "
                    + "the host did not pass");
        }
        device = warm;
    }

    private boolean rebuild() {
        closeQuietly();
        sleep(400L);
        UsbDevice next = find(manager());
        if (next == null) {
            return false;
        }
        try {
            device = next;
            attach(next);
        armReason = "device re-attached by the host (the node changed)";
            return true;
        } catch (RuntimeException | TunerException failure) {
            return false;
        }
    }

    private boolean tuneOnce(int khz) {
        if (tuner == null || khz <= 0) {
            return false;
        }
        try {
            locked = tuneAndScan(khz);
            rearm();
            return locked;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    /**
     * Stop the reader and wait for its blocking transfers to come back. Measured: a register read while the pump
     * is armed costs the stream (0 bytes afterwards), so every read this package makes pauses first.
     */
    /**
     * Stop the reader and wait for its thread to LEAVE. Returns whether it actually left: a host whose sink is
     * expensive (`onData -> demux.feed` inline on the reader's writer thread) can keep that thread inside its own code
     * when this instance pauses, and re-arming then races the host exactly as it raced the bulk timeout at the 1.2 s
     * join (IT9175's hypothesis, 2026-11-23). The caller re-arms only on a confirmed exit.
     */
    private boolean pauseReader() {
        running = false;
        Thread thread = readerThread;
        readerThread = null;
        if (thread == null) {
            return true;
        }
        thread.interrupt();
        long paused = System.currentTimeMillis();
        try {
            long waited = 0;
            while (thread.isAlive() && waited < 8000) {
                thread.join(250L);
                waited = System.currentTimeMillis() - paused;
            }
            boolean stopped = !thread.isAlive();
            Log.i(TAG, "reader paused: joined in " + waited + " ms, stopped " + stopped
                    + (stopped ? "" : " (still inside the host's sink; NOT re-arming)"));
            return stopped;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Reads per second over the interval since the last print - the number that turns a rate into a mechanism. */
    private static double readsPerSecond(long total, long previous, long sinceMillis) {
        long elapsed = System.currentTimeMillis() - sinceMillis;
        if (elapsed <= 0) {
            return 0;
        }
        return (total - previous) * 1000.0 / elapsed;
    }

    /** Largest completion gap, when the transport can report one (0 otherwise). */
    private static long gapMs(Transport live) {
        return live.maxCompletionGapMs();
    }

    /** Largest completion gap in the current reporting window (0 when the source cannot report one). */
    private static long windowGapMs(Transport live) {
        return live.windowMaxGapMs();
    }

    /** Completions that followed a stall, when the transport can report them (0 otherwise). */
    private static int slowCount(Transport live) {
        return live.slowCompletions();
    }

    private void startReader() {
        if (running || transport == null) {
            return;
        }
        running = true;
        final Transport live = transport;
        // the pool size is decided HERE so the log can print it: HEAD noted that nothing logged it, which left
        // "one consumer ran" as likely rather than measured even after the host's row.
        final int consumers = live.orderedSource() ? 1 : com.pbcnx.drivers.lme2510.TsReader.READERS;
        Log.i(TAG, "reader thread starting, armed at " + frequencyKhz + " kHz, consumers " + consumers
                + ", ordered source " + live.orderedSource()
                + (consumers == 1 ? " (one consumer: the queue is the order)" : " (pooled)"));
        readerThread = new Thread(() -> {
            // ONE consumer when the source is already ordered (HEAD, 2026-11-23). The prediction that this would
            // fall the continuity errors to near zero was NOT SUPPORTED by the host's row the same afternoon: cc stayed at
            // ~1.5 % of packets with the same rate (352b6b8a, 85 and 200 completions, interval 10..95 ms). "Not
            // supported" is the host's word and it is the accurate one - HEAD noted that nothing logged the pool size, so
            // until this line prints it, even "one consumer ran" was likely rather than measured. It is kept
            // because a pool cannot assign a sequence number to a chunk it never received - the class of fault is
            // gone by construction - and because the measured cost is zero. The cc is therefore either on the air or
            // counts duplicates as discontinuities, and the instrument that would say which is a DUPLICATE counter
            // beside cc, not another reader change.
            com.pbcnx.drivers.lme2510.TsReader created =
                    new com.pbcnx.drivers.lme2510.TsReader(live, consumers, com.pbcnx.drivers.lme2510.TsReader.CHUNK);
            reader = created;
            com.pbcnx.drivers.lme2510.TsReader.Sink sink = new com.pbcnx.drivers.lme2510.TsReader.Sink() {
                private boolean dispatched;

                @Override
                public void write(byte[] chunk) {
                    if (!dispatched) {
                        dispatched = true;
                        // First-byte latency, IT9175 absence A: the total is not the product - 588 KiB/s throughout and
                        // two seconds of nothing then 785 KiB/s read the same in a byte count.
                        firstByteAt = System.currentTimeMillis();
                        Log.i(TAG, "first dispatch to the host: " + chunk.length + " B at "
                                + (firstByteAt - tunedAt) + " ms after the tune at " + frequencyKhz + " kHz");
                        // The launch stall the host measured (1.9 to 13.4 s before the rate settles) can only be attributed
                        // if each stage has a stamp: attach -> init -> tune -> gate -> first byte.
                        Log.i(TAG, "startup timeline: init " + (initDoneAt - attachedAt) + " ms, tune "
                                + (tunedAt - initDoneAt) + " ms, gate->first byte " + (firstByteAt - tunedAt)
                                + " ms, total " + (firstByteAt - attachedAt) + " ms");
                    }
                    bytes.addAndGet(chunk.length);
                    quality.feed(chunk, chunk.length, System.nanoTime() / 1e9);
                    if (listener != null) {
                        listener.onData(chunk, chunk.length);
                    }
                }
            };
            // A FRESH reader every 500 ms, which is how the host glue that streams drives this same core
            // (the host's Lme2510TunerSource: `new TsReader(transport)` per pass, `run(500L, chunk -> sink)`).
            //
            // The first version of this loop did the same thing and delivered ZERO bytes: every 500 ms pass left
            // its own blocked bulk-read threads behind, so ten passes stacked competing reads on one endpoint
            // (LEAP 2026-11-22). That is fixed in the reader, not here - TsReader.run() now waits for every pool
            // thread to leave before it returns, and reports any survivor. With that guarantee, a pass cannot
            // stack: a stalled endpoint is torn down and its transfers re-issued every 500 ms, which is the only
            // thing measured to recover one (the door held one pool for 60 s and read -1 forever, 2026-11-23).
            // The gate is not a one-shot. A door that tunes once and then reads (the host's shape) can end up locked,
            // gated and silent - the row that read `rc -1` for twelve seconds - and nothing inside the package ever
            // asked the demod again. So the pass loop watches the byte counter: a few silent passes while locked and
            // armed means the pipe was never really opened, and the package re-sends the two frames itself.
            //
            // This is safe because the reader is STOPPED between passes (TsReader.run() waits for its pool), so the
            // gate goes out on an idle endpoint - the same order the CLI uses.
            long alignerDropped = 0;
            long passes = 0;
            long chunksTotal = 0;
            long packetsTotal = 0;
            long lastPackets = 0;
            long lastChunks = 0;
            long lastPrintAt = System.currentTimeMillis();
            int silentPasses = 0;
            int regates = 0;
            long lastRegateAt = 0L;
            long bytesAtStart = bytes.get();
            while (running) {
                com.pbcnx.drivers.lme2510.TsReader pass = new com.pbcnx.drivers.lme2510.TsReader(live);
                reader = pass;
                long before = bytes.get();
                try {
                    // 2 s, not 500 ms. The pass boundary is now the ONLY repeating seam left in the pipeline:
                    // each pass tears the reader down, waits for its pool, and starts a fresh aligner, and the host measured
                    // the effect as VARIANCE rather than a ceiling (99-103 % of a known-good control in one row, 76 %
                    // in the next, 0.5 % gaps, fourteen player state changes in 35 s). With the async pipe owning the
                    // transfers, the pass length no longer has to be short for the pump to be re-issued - it only sets
                    // how often the silence check runs - so 2 s keeps the check and makes the seam four times rarer.
                    pass.run(2000L, sink, null);
                } catch (RuntimeException failed) {
                    if (running) {
                        Log.w(TAG, "reader pass ended: " + failed);
                    }
                    break;
                }
                if (!running) {
                    break;
                }
                passes++;
                try {
                    alignerDropped += pass.alignerDiscarded();
                    chunksTotal += pass.chunksRead();
                    packetsTotal = quality.packetsSeen();
                } catch (RuntimeException ignored) {
                    // a pass that never started has nothing to report
                }
                if (passes % 20 == 0) {
                    // every ~10 s: the three numbers that close the boundary between harvest and sink
                    Log.i(TAG, "reader: " + bytes.get() + " B to the sink, aligner dropped " + alignerDropped
                            + " B, " + passes + " passes, gaps " + quality.gaps()
                            + " (by pid: " + quality.gapsByPid(4)
                            + "); heaviest pids: " + quality.heaviestPids(4)
                            + String.format(java.util.Locale.US, ", %.1f reads/s (in flight %d B)",
                                    readsPerSecond(chunksTotal, lastChunks, lastPrintAt), 4 * 51200)
                            + ", duplicates " + quality.duplicates()
                            + String.format(java.util.Locale.US,
                                    ", arm %d, heap %d MiB, threads %d, max gap %d ms (window %d ms), slow %d",
                                    armCount,
                                    (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
                                            / (1024 * 1024),
                                    Thread.activeCount(), gapMs(live), windowGapMs(live), slowCount(live))
                            + String.format(java.util.Locale.US,
                                    ", ts %.0f packets/s, unevaluated %d%%, mux %.0f KiB/s from PCR (%d samples)",
                                    readsPerSecond(packetsTotal, lastPackets, lastPrintAt),
                                    quality.packetsSeen() == 0 ? 0
                                            : (100 * quality.nullPackets() / quality.packetsSeen()),
                                    quality.muxKiBPerSecond(), quality.muxSamples()));
                    lastChunks = chunksTotal;
                    lastPackets = packetsTotal;
                    lastPrintAt = System.currentTimeMillis();
                }
                boolean delivered = bytes.get() != before;
                if (delivered) {
                    if (silentPasses > 0) {
                        Log.i(TAG, "pipe woke after " + silentPasses + " silent pass(es), "
                                + (bytes.get() - bytesAtStart) + " B total");
                    }
                    silentPasses = 0;
                } else {
                    silentPasses++;
                    // The gate is for a pump that NEVER opened, not for one that is merely slow. Measured 2026-11-23:
                    // with the async queue a completion can take ~2 s at a low mux rate, and re-gating inside that
                    // window restarts the pump and loses the transfer that was about to complete - the re-gate was
                    // making the rate worse than the silence it was answering. So: re-send only while NO byte has
                    // arrived since arming; once the pipe has delivered anything, silence is reported and left alone.
                    boolean neverDelivered = bytes.get() == bytesAtStart;
                    if (silentPasses == 3 && armed && neverDelivered) {
                        regates++;
                        Log.i(TAG, "pipe silent for 3 passes while locked and armed - re-sending the gate (re-gate "
                                + regates + ")");
                        try {
                            rearm();
                        } catch (RuntimeException failed) {
                            Log.w(TAG, "re-gate failed: " + failed);
                        }
                        silentPasses = 0;
                    } else if (silentPasses >= 4 && !neverDelivered) {
                        // A PROVEN pipe that goes quiet is NO LONGER re-gated, and the reason is measured (2026-11-23):
                        //
                        //   the re-gate was added when the pump died after nine completions - that was the duplicate
                        //   re-queue, now fixed. Since then the rule fires on a healthy pipe, and a gate is a COMMAND
                        //   on the command endpoint while four URBs are in flight; this chip's oldest rule is that a
                        //   command while the pump runs disturbs the stream. the host's A/B against a known-good tuner on the
                        //   same antenna is the evidence: the control loses 0.005 % of packets and the LME loses 0.48 %,
                        //   with the player changing state fourteen times in 35 s against the control's twice - and the
                        //   completions show multi-second stalls (interval 10..2554 ms) that a command write explains
                        //   and a healthy USB pipe does not.
                        //
                        // So: log it, keep the number, do not touch the device. If a row shows the pipe going quiet and
                        // NEVER coming back, the never-delivered branch still re-gates up to three times - that path is
                        // for a gate that was never accepted, which is a different state.
                        if (silentPasses % 4 == 0) {
                            Log.i(TAG, "pipe quiet for 2 s after delivering (" + bytes.get() + " B so far) - NOT "
                                    + "re-gating: a proven pipe is left alone (a command would disturb four URBs in "
                                    + "flight)");
                        }
                        if (silentPasses >= 40) {
                            silentPasses = 0;      // keep the counter bounded; the log above is rate-limited by modulo
                        }
                    } else if (regates >= 3 && silentPasses >= 3 && neverDelivered) {
                        Log.i(TAG, "three re-gates and still silent - asking the host for a recover()");
                        silentPasses = 0;
                        break;
                    }
                }
                if (!attached()) {
                    Log.i(TAG, "reader: the device is not enumerated any more; the host's resume() is next");
                    break;
                }
            }
        }, "lme2510-reader");
        readerThread.start();
    }

    /**
     * Refuse a device object that is no longer the one the bus enumerates. The host may hold a survivor from an earlier
     * attach (the host's app has an attach watcher, a firmware store and four other drivers on the same bus), and its
     * {@code openDevice} can succeed while the TS endpoint it resolves belongs to a device that has re-enumerated since.
     */
    private void checkNotStale(UsbDevice target) throws TunerException {
        UsbManager manager = manager();
        if (manager == null || target == null) {
            return;
        }
        String name = target.getDeviceName();
        if (name == null) {
            return;
        }
        for (UsbDevice candidate : manager.getDeviceList().values()) {
            if (name.equals(candidate.getDeviceName())
                    && candidate.getVendorId() == target.getVendorId()
                    && candidate.getProductId() == target.getProductId()) {
                return;
            }
        }
        throw new TunerException("LME2510: the device object passed to open() is not the one the bus is enumerating "
                + "(node " + name + " is gone); the host must pass the device from the CURRENT attach - a survivor "
                + "opens, resolves endpoints and delivers nothing");
    }

    private boolean attached() {
        UsbDevice held = device;
        UsbManager manager = manager();
        if (held == null || manager == null) {
            return false;
        }
        String name = held.getDeviceName();
        if (name == null) {
            return false;
        }
        for (UsbDevice candidate : manager.getDeviceList().values()) {
            if (name.equals(candidate.getDeviceName())) {
                return true;
            }
        }
        return false;
    }

    private boolean waitForBytes(long millis) {
        long before = bytes.get();
        long deadline = System.currentTimeMillis() + Math.max(50L, millis);
        while (System.currentTimeMillis() < deadline) {
            if (bytes.get() > before) {
                return true;
            }
            if (closed) {
                return false;
            }
            sleep(25L);
        }
        return bytes.get() > before;
    }

    /** {@code EMPTY}/{@code FULL} in dB, the gate correction, and the gate's open limit (lme2510/rssi.py). */
    private static final int RSSI_EMPTY = -70;
    private static final int RSSI_FULL = -35;
    private static final int RSSI_CORRECTION = 6;
    private static final int GATE_LIMIT = 0x5F;

    /** The signed level in dB as the Python core reports it; {@link Integer#MIN_VALUE} when it did not answer. */
    private int readLevel() {
        int raw = tuner.readRegisterByte(Lme2510Device.RSSI);
        if (raw < 0) {
            return Integer.MIN_VALUE;
        }
        int level = raw > 0x7F ? raw - 256 : raw;
        try {
            int gate = tuner.readRegisterByte(Lme2510Device.RSSI_GATE);
            if (gate >= 0 && (gate & 0x7F) <= GATE_LIMIT) {
                level += RSSI_CORRECTION;
            }
        } catch (RuntimeException noGate) {
            // a gate that does not answer means no correction, not no reading
        }
        return level;
    }

    private static int percentOf(int level) {
        int span = RSSI_FULL - RSSI_EMPTY;
        int value = (level - RSSI_EMPTY) * 100 / span;
        return Math.max(0, Math.min(100, value));
    }

    private void closeQuietly() {
        running = false;
        if (readerThread != null) {
            readerThread.interrupt();
            readerThread = null;
        }
        if (tuner != null) {
            try {
                tuner.close();
            } catch (RuntimeException ignored) {
                // already gone
            }
            tuner = null;
        }
        if (transport != null) {
            try {
                transport.close();
            } catch (RuntimeException ignored) {
                // already gone
            }
            transport = null;
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Our decoder's record mapped onto the door's canonical shape (TUNER-CONTRACT 1.4, section 5). */
    static Tmcc map(com.pbcnx.drivers.lme2510.Tmcc record) {
        if (record == null) {
            return null;
        }
        Tmcc.Layer[] canonical = new Tmcc.Layer[]{
                layerOf(record.layers[0]), layerOf(record.layers[1]), layerOf(record.layers[2])
        };
        // Contract 1.0.1 (2026-11-22) relaxes valid() to the segment rule, so a record with mode/guard -1 reads
        // VALID here - which is the 1.2 wording ("known, or absent by design") finally true in the type. The
        // trade this line accepted: a part that CAN read mode/guard and returns -1 would also pass, so that
        // defect is no longer visible through valid() and must be caught elsewhere (4.8 keeps it a defect in
        // prose). We asked for capability flags instead; the door chose the cheaper fix, and this comment is the
        // record so the next reader does not have to rediscover the trade.
        return new Tmcc(-1, -1, record.partial, record.ewbsValue(), canonical);
    }

    private static Tmcc.Layer layerOf(com.pbcnx.drivers.lme2510.Tmcc.Layer layer) {
        if (layer == null || !layer.present) {
            return new Tmcc.Layer(0, -1, -1, -1);
        }
        return new Tmcc.Layer(layer.segments, layer.modulation, layer.codeRate, layer.interleaving);
    }
}
