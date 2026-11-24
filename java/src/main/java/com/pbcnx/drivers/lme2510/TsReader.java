/*
 * Copyright (c) 2026 The LME2510 Open Driver Authors.  MIT.
 *  Marker: lme2510-pbcnx.
 */
package com.pbcnx.drivers.lme2510;

import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reading the TS endpoint: several bulk reads in flight, written in order.
 *
 * <p>The vendor keeps four URBs of 51,200 B in flight, so a small pool does the
 * same. Within the pool each chunk is numbered <em>before</em> its read and a single
 * writer emits them in sequence order — without that, chunks land in completion
 * order and packets cross boundaries (the Python side measured 339 continuity
 * jumps in an 8 s run against 2 with the ordered writer).
 */
public final class TsReader {

    public static final int CHUNK = 51200;   // the geometry that streams: lme2510/reader.py CHUNK = 51200 with a 500 ms
                                          // timeout and four readers - measured 565.8 KiB/s at 497143 and 914.1 KiB/s
                                          // at 479143 on this hardware (2026-11-23). 15,040 / 300 ms is read_ts()'s
                                          // single-shot probe default, NOT the streaming path.
    public static final int READERS = 4;
    /** How long the byte counter may stand still before the presence question is asked. */
    public static final long SILENCE_MS = 2000L;

    /** Where the bytes go. */
    public interface Sink {
        void write(byte[] chunk);
    }

    private final Transport transport;
    private final int readers;
    private final int chunk;
    /**
     * Whole packets leave the WRITER, which is the only place chunks are in sequence order. A bulk transfer ends
     * at whatever length the device chose and 51,200 is not a multiple of 188, so a host that parses unaligned
     * data never recovers (Python, 2026-11-23: per-read alignment gave two seams and 14 continuity errors in 30 s;
     * alignment in the ordered writer gave 100 %).
     */
    private final PacketAligner aligner = new PacketAligner();
    /** Bytes the aligner threw away during the last run - the package's only possible loss before the sink. */
    public long alignerDiscarded() {
        return aligner.discarded();
    }

    /** How many pool threads were still alive when the last run stopped: 0 is the only safe number. */
    public volatile int lastStopSurvivors;

    /** Reads completed during the last run, so a rate can be quoted beside its reader cost. */
    private volatile long chunksRead;

    public TsReader(Transport transport) {
        this(transport, READERS, CHUNK);
    }

    public TsReader(Transport transport, int readers, int chunk) {
        this.transport = transport;
        this.readers = readers;
        this.chunk = chunk;
    }

    /** Read for {@code millis} milliseconds; returns the byte count. */
    public long run(final long millis, final Sink sink) {
        return run(millis, sink, null);
    }

    /**
     * Raised when the stream goes quiet and the device is no longer attached.
     *
     * <p>Measured on the LEAP (Android 14) 2026-11-20: after a reset writes {@code 0a 00}, the bulk reads
     * return nothing instead of failing, so a reader that only looks at exceptions reads a dead pipe for the
     * rest of its window and the supervisor is never told. A detach is <b>silence and absence</b>: the
     * silence comes from the byte counter, the absence from {@link Presence}.</p>
     */
    public static final class DeviceGone extends RuntimeException {
        DeviceGone(String message) {
            super(message);
        }
    }

    /** "Is the device still enumerated?" - a bus query, never a device read. */
    public interface Presence {
        boolean attached();
    }

    /**
     * Read for {@code millis} milliseconds, giving up early if the device leaves the bus.
     *
     * @param sink     receives the TS bytes
     * @param presence asked after {@link #SILENCE_MS} of no data; {@code null} keeps the old behaviour
     * @throws DeviceGone when nothing has arrived for {@link #SILENCE_MS} and {@code presence} says absent
     */
    public long run(final long millis, final Sink sink, final Presence presence) {
        final Object lock = new Object();
        final TreeMap<Integer, byte[]> pending = new TreeMap<>();
        final AtomicInteger sequence = new AtomicInteger();
        final int[] next = {0};
        final long[] total = {0};
        // Reads completed in this run, so a rate can always be quoted with its reader cost - a rate without it is a
        // symptom rather than a mechanism (IT9175, 2026-11-23).
        final long[] chunks = {0};
        final boolean[] stopping = {false};
        final boolean[] gone = {false};
        final java.util.concurrent.atomic.AtomicInteger activeReaders =
                new java.util.concurrent.atomic.AtomicInteger(readers);

        Thread writer = new Thread(() -> {
            while (true) {
                byte[] data;
                synchronized (lock) {
                    while (!pending.containsKey(next[0])) {
                        if (stopping[0]) {
                            if (pending.isEmpty()) {
                                if (activeReaders.get() == 0) {
                                    return;      // every reader has left and everything it produced was emitted
                                }
                                try {
                                    lock.wait(50L);   // a reader is still in flight: wait for its chunk
                                } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                    return;
                                }
                                continue;
                            }
                            // a hole no reader will fill: resume at the oldest chunk
                            next[0] = pending.firstKey();
                            break;
                        }
                        try {
                            lock.wait(50L);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                    data = pending.remove(next[0]);
                    next[0]++;
                }
                if (data != null && data.length > 0) {
                    byte[] whole = aligner.push(data);
                    if (whole.length > 0) {
                        sink.write(whole);
                        total[0] += whole.length;
                    }
                }
            }
        }, "lme2510-ts-writer");

        Thread[] pool = new Thread[readers];
        for (int index = 0; index < readers; index++) {
            pool[index] = new Thread(() -> {
                while (true) {
                    int seq = sequence.getAndIncrement();
                    byte[] data;
                    try {
                        data = transport.read(chunk);
                    } catch (RuntimeException failure) {
                        data = new byte[0];
                    }
                    boolean leaving;
                    synchronized (lock) {
                        // ALWAYS put: a chunk that has already been read is bytes the device gave us, and discarding
                        // it here was the largest loss in this package - 4 readers x 51,200 B at every 500 ms pass
                        // boundary, which is the 45 % gap the host measured (2026-11-23). The writer flushes what is left
                        // when the run stops, so the chunk is delivered and only then does this thread leave.
                        pending.put(seq, data == null ? new byte[0] : data);
                        if (data != null && data.length > 0) {
                            chunks[0]++;
                        }
                        leaving = stopping[0];
                        if (leaving) {
                            activeReaders.decrementAndGet();
                        }
                        lock.notifyAll();
                    }
                    if (leaving) {
                        return;
                    }
                }
            }, "lme2510-ts-reader-" + index);
        }

        writer.start();
        for (Thread reader : pool) {
            reader.start();
        }
        // Wait in short steps so a vanished device ends the run instead of being waited out.
        final long deadline = System.currentTimeMillis() + millis;
        long lastTotal = 0;
        long lastChange = System.currentTimeMillis();
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(100L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
            long now = System.currentTimeMillis();
            if (total[0] != lastTotal) {
                lastTotal = total[0];
                lastChange = now;
                continue;
            }
            if (presence == null || now - lastChange < SILENCE_MS) {
                continue;
            }
            boolean attached;
            try {
                attached = presence.attached();
            } catch (RuntimeException cannotTell) {
                attached = true;             // cannot tell: do not accuse the device
            }
            if (!attached) {
                gone[0] = true;
                break;
            }
            lastChange = now;                // asked and answered: do not re-ask every tick
        }
        synchronized (lock) {
            stopping[0] = true;
            lock.notifyAll();
        }
        // Wait for EVERY pool thread to leave, not for a fixed 1000 ms. A reader that is still blocked in a
        // bulkTransfer when this returns outlives the run, and the next run() - the one a pause and a re-arm
        // creates - puts a second pool on the same endpoint: that is the stacked-reader failure this class
        // documents (ten runs in five seconds, ZERO bytes while the direct path streamed), and a fixed join is
        // exactly how it comes back.
        long stopDeadline = System.currentTimeMillis() + 3000L;
        for (Thread reader : pool) {
            while (reader.isAlive() && System.currentTimeMillis() < stopDeadline) {
                try {
                    reader.join(100L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        int survivors = 0;
        for (Thread reader : pool) {
            if (reader.isAlive()) {
                survivors++;
            }
        }
        lastStopSurvivors = survivors;
        try {
            writer.join(2000L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        if (gone[0]) {
            throw new DeviceGone("no data for " + SILENCE_MS + " ms and the device is not attached");
        }
        chunksRead = chunks[0];
        return total[0];
    }

    /** Reads completed during the last run. */
    public long chunksRead() {
        return chunksRead;
    }
}
