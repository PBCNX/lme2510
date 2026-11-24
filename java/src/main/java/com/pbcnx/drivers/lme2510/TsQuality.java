/*
 * Copyright (c) 2026 The LME2510 Open Driver Authors.  MIT.
 *  Marker: lme2510-pbcnx.
 */
package com.pbcnx.drivers.lme2510;

import java.util.ArrayDeque;

/**
 * Stream quality by the studio's shared definition, the same rule as {@code lme2510.quality}:
 *
 * <p>{@code quality % over a sliding window (default 5 s) = 100 * clean / total}.</p>
 *
 * <p>A packet is not clean when its transport_error_indicator is set or when it breaks the
 * continuity counter of its PID. A repeated counter is not a break. Null packets
 * ({@code 0x1FFF}) and adaptation-field-only packets are not evaluable and are skipped by
 * both the numerator and the denominator.</p>
 *
 * <p>It says how intact the stream we received is; it is not a C/N measurement, and it is
 * not comparable across drivers unless the window is. A percentage is unknown (NaN) until
 * a full window has been observed — an early number from 0.3 s of stream is noise dressed
 * as a measurement.</p>
 */
public final class TsQuality {

    /** The window the studio's definition uses, in seconds. */
    public static final double WINDOW = 5.0;

    private static final int PACKET = 188;
    private static final int NULL_PID = 0x1FFF;
    private static final int MAX_PID = 0x2000;

    private final double windowSeconds;
    private final int[] lastCounter = new int[MAX_PID];
    private final boolean[] seen = new boolean[MAX_PID];
    /**
     * Guarded by its own monitor, because it has two owners: the writer thread adds a sample for every chunk
     * ({@link #mark}) and the host asks for the window from its own thread ({@link #percent}). Measured 2026-11-23:
     * a plain ArrayDeque here threw NullPointerException on the writer thread within two minutes of the first
     * build that delivered bytes - size() was true at the instant peekFirst() had been drained by the other
     * thread. The deque is not the only shared state: total/unclean/started are read under the same monitor.
     */
    private final ArrayDeque<double[]> samples = new ArrayDeque<>();
    private final byte[] tail = new byte[PACKET];
    private int tailLength;

    private long total;
    /**
     * Packets whose continuity counter repeated the previous value for the same PID. A receiver that gets a
     * complete stream out of order, or a mux that repeats a packet, shows these; a lossy stream shows gaps
     * instead. The contract has no field for it, so it is exposed here and printed by any host that counts
     * (the host, 2026-11-23: the next instrument beside cc is a duplicate counter, not another reader change).
     */
    private long duplicates;
    /**
     * The transport stream's OWN rate, derived from the PCR clock in the stream. This is the only number that can say
     * whether a control's 687 KiB/s is the transmitter's rate or that receiver's ceiling: the mux carries a 27 MHz
     * clock, so bytes-between-two-PCRs over the clock difference is the rate the multiplex was built at, independent
     * of which tuner is reading it (the host/owner, 2026-11-23: "one field turns 85 % of a control into 584 of 690").
     */
    private final java.util.ArrayDeque<long[]> pcrSamples = new java.util.ArrayDeque<long[]>();
    /** Bytes parsed, monotonic: the numerator of the PCR ratio. */
    private long parsedBytes;
    private double muxBytesPerSecond = Double.NaN;
    private int pcrSamplesUsed;
    /** The one PID whose PCRs this window follows. */
    private int pcrPid = -1;
    /**
     * Discontinuities per PID. A single PID that carries a steady service losing packets at a constant rate says
     * the AIR lost them; a burst of gaps across every PID inside one window says a CHUNK went missing on the bus
     * or in the hand-off. That is the distinction the host asked for on 2026-11-23, and one array answers it.
     */
    private final int[] gapsPerPid = new int[MAX_PID];
    /**
     * Packets per PID, so a gap can be expressed as a SHARE of that PID's traffic. the host, 2026-11-23: without the
     * share, four PIDs with gaps in a stable ranking is "consistent with air"; with it, the row is a verdict -
     * a constant share per PID is the air, and a disproportionate share on a low-traffic PID is a burst (a chunk).
     */
    private final int[] packetsPerPid = new int[MAX_PID];
    private long unclean;
    private long desync;
    private double started = Double.NaN;

    public TsQuality() {
        this(WINDOW);
    }

    public TsQuality(double windowSeconds) {
        this.windowSeconds = windowSeconds;
        java.util.Arrays.fill(lastCounter, -1);
    }

    /** Feed one chunk; {@code now} is seconds from any monotonic clock. */
    public void feed(byte[] data, int length, double now) {
        // The stream slips alignment (stray bytes at a ~2 KiB boundary, measured on
        // Windows 2026-11-18), so a fixed 188-byte step would turn one slip into garbage
        // for the rest of the run — and a quality number from that is worse than none.
        byte[] buffer = new byte[tailLength + length];
        System.arraycopy(tail, 0, buffer, 0, tailLength);
        System.arraycopy(data, 0, buffer, tailLength, length);
        int end = buffer.length;
        int index = 0;
        while (true) {
            int start = packetStart(buffer, index, end);
            if (start < 0) {
                break;
            }
            if (start != index) {
                desync++;
            }
            parse(buffer, start);
            index = start + PACKET;
        }
        tailLength = end - index;
        System.arraycopy(buffer, index, tail, 0, tailLength);
        mark(now);
    }

    /** Next whole packet at or after {@code index}, resyncing; -1 when there is none. */
    private static int packetStart(byte[] data, int index, int end) {
        if (index + PACKET > end) {
            return -1;
        }
        if ((data[index] & 0xFF) == 0x47) {
            return index;
        }
        int scan = index + 1;
        while (scan + PACKET <= end) {
            if ((data[scan] & 0xFF) == 0x47) {
                int following = scan + PACKET;
                if (following + PACKET > end || (data[following] & 0xFF) == 0x47) {
                    return scan;
                }
            }
            scan++;
        }
        return -1;
    }

    private void parse(byte[] data, int offset) {
        if ((data[offset] & 0xFF) != 0x47) {
            return;                      // not a packet boundary: not evaluable
        }
        // EVERY packet counts toward the PCR ratio - and the first version of this counter did not, which is why it read
        // 500 where the capture's own PCR says 579 (0.863 of the truth). The reason is not nulls, which this mux does not
        // carry (0.00 % on the capture): it is that ADAPTATION-ONLY packets (adaptation_field_control = 2 or 0, 9.28 % of
        // the capture) return early from this evaluator - and those are precisely the packets a PCR rides in. Counting
        // bytes here, before any of the early returns, is what makes the ratio honest (measured 2026-11-23: 500 -> 579).
        parsedBytes += PACKET;
        int pid = ((data[offset + 1] & 0x1F) << 8) | (data[offset + 2] & 0xFF);
        if (pid == NULL_PID) {
            return;
        }
        int control = data[offset + 3] & 0xFF;
        // PCR, appended to a WINDOW of samples rather than compared against one base. The first version of this
        // instrument kept a single base and reported 1 and 13 KiB/s while the sink carried 580-590 (the host, 2026-11-23);
        // a base that a re-tune moves, or one whose delta the comparison rejects, measures the wrong interval and
        // nothing in the line says so. The window prints its own sample count so a bad reading is visible.
        if ((control & 0x30) == 0x30 && (data[offset + 4] & 0xFF) > 0 && (data[offset + 5] & 0x10) != 0) {
            int a = data[offset + 6] & 0xFF;
            int b = data[offset + 7] & 0xFF;
            int c = data[offset + 8] & 0xFF;
            int d = data[offset + 9] & 0xFF;
            int e = data[offset + 10] & 0xFF;
            int f = data[offset + 11] & 0xFF;
            long base = ((long) a << 25) | ((long) b << 17) | ((long) c << 9) | ((long) d << 1) | (e >> 7);
            long extension = ((long) (e & 0x01) << 8) | f;
            pcrSample(pid, base * 300L + extension);
        }
        int adaptation = (control >> 4) & 0x03;
        if (adaptation != 1 && adaptation != 3) {
            return;                      // no payload: the counter does not advance
        }
        boolean tei = (data[offset + 1] & 0x80) != 0;
        int counter = control & 0x0F;
        boolean broken = false;
        if (seen[pid]) {
            int expected = (lastCounter[pid] + 1) & 0x0F;
            if (counter == lastCounter[pid]) {
                duplicates++;            // a duplicate: the same counter twice, which is not a break
            } else if (counter != expected) {
                broken = true;           // a gap: the counter skipped
                gapsPerPid[pid]++;
            }
        }
        seen[pid] = true;
        lastCounter[pid] = counter;
        total++;
        packetsPerPid[pid]++;
        if (tei || broken) {
            unclean++;
        }
    }

    private void mark(double now) {
        synchronized (samples) {
            if (Double.isNaN(started)) {
                started = now;
            }
            samples.addLast(new double[]{now, total, unclean});
            while (samples.size() > 2 && now - samples.peekFirst()[0] >= windowSeconds) {
                samples.removeFirst();
            }
        }
    }

    /** Quality over the window, or NaN until a full window has been observed. */
    public double percent(double now) {
        synchronized (samples) {
            if (Double.isNaN(started) || samples.isEmpty() || now - started < windowSeconds) {
                return Double.NaN;
            }
            double[] base = samples.peekFirst();
            if (base == null) {
                return Double.NaN;
            }
            long windowTotal = total - (long) base[1];
            long windowUnclean = unclean - (long) base[2];
            if (windowTotal <= 0) {
                return Double.NaN;
            }
            return 100.0 * (windowTotal - windowUnclean) / windowTotal;
        }
    }

    /** "0x094E:12 0x094F:3 0x02EE:1" - the PIDs with the most gaps, most first. */
    public String gapsByPid(int top) {
        StringBuilder out = new StringBuilder();
        int shown = 0;
        boolean[] used = new boolean[MAX_PID];
        while (shown < top) {
            int best = -1;
            for (int pid = 0; pid < MAX_PID; pid++) {
                if (!used[pid] && gapsPerPid[pid] > 0
                        && (best < 0 || gapsPerPid[pid] > gapsPerPid[best])) {
                    best = pid;
                }
            }
            if (best < 0) {
                break;
            }
            used[best] = true;
            if (shown > 0) {
                out.append(' ');
            }
            out.append(String.format(java.util.Locale.US, "0x%04X:%d/%d", best, gapsPerPid[best],
                    packetsPerPid[best]));
            shown++;
        }
        return out.toString();
    }

    /**
     * The PIDs carrying the most packets, with their gap counts: "0x094E:0/41500".
     *
     * <p>the host, 2026-11-23: the question a viewer asks is not "did the mux lose packets" but "did the PICTURE's
     * packets get lost" - and in their row the watched service's own video and audio PIDs carried NO gaps while
     * sparse PIDs carried 0.1-7 %. A list ranked by gaps cannot show that; a list ranked by traffic can.</p>
     */
    public String heaviestPids(int top) {
        StringBuilder out = new StringBuilder();
        boolean[] used = new boolean[MAX_PID];
        for (int shown = 0; shown < top; shown++) {
            int best = -1;
            for (int pid = 0; pid < MAX_PID; pid++) {
                if (!used[pid] && packetsPerPid[pid] > 0
                        && (best < 0 || packetsPerPid[pid] > packetsPerPid[best])) {
                    best = pid;
                }
            }
            if (best < 0) {
                break;
            }
            used[best] = true;
            if (shown > 0) {
                out.append(' ');
            }
            out.append(String.format(java.util.Locale.US, "0x%04X:%d/%d", best, gapsPerPid[best],
                    packetsPerPid[best]));
        }
        return out.toString();
    }

    /**
     * One PCR reading with the bytes parsed so far. Kept only if the clock advanced at least 50 ms; the window drops
     * samples older than five seconds; the rate needs a span of at least one second, or it is NaN.
     */
    private void pcrSample(int pid, long pcr) {
        // ONE PID's PCRs only. A multiplex can carry a PCR per program, and their values are offset from each other by a
        // few ticks, so mixing PIDs made the rate oscillate 489-560 KiB/s on a capture whose own first-to-last PCR says
        // 579 (measured 2026-11-23 with this very code). The window adopts the first PID that produces a sample and
        // switches only while it still has too few samples to matter.
        if (pcrPid < 0) {
            pcrPid = pid;                       // lock onto the first PCR PID seen, permanently
        }
        if (pid != pcrPid) {
            return;                             // another program's PCR: offset from this one, never mixed in
        }
        long[] newest = pcrSamples.peekLast();
        if (newest != null && pcr <= newest[0]) {
            return;                                  // repeated or rewound clock: not a new sample
        }
        if (newest != null && (pcr - newest[0]) < 27000000L / 20) {
            return;                                  // under 50 ms of clock: too fine to beat parse jitter
        }
        pcrSamples.addLast(new long[] {pcr, parsedBytes});
        while (pcrSamples.size() > 40) {
            pcrSamples.removeFirst();
        }
        long[] oldest = pcrSamples.peekFirst();
        while (pcrSamples.size() > 2 && oldest != null && pcrSamples.peekLast() != null
                && (pcrSamples.peekLast()[0] - oldest[0]) > 5L * 27000000L) {
            pcrSamples.removeFirst();
            oldest = pcrSamples.peekFirst();
        }
        pcrSamplesUsed = pcrSamples.size();
        oldest = pcrSamples.peekFirst();
        long[] last = pcrSamples.peekLast();
        if (oldest == null || last == null || last == oldest) {
            muxBytesPerSecond = Double.NaN;
            return;
        }
        long ticks = last[0] - oldest[0];
        long bytes = last[1] - oldest[1];
        if (ticks < 27000000L || bytes <= 0) {
            muxBytesPerSecond = Double.NaN;           // under a second of clock cannot support a rate
            return;
        }
        muxBytesPerSecond = bytes * 27000000.0 / ticks;
    }

    /** The transport stream's own rate in KiB/s from the PCR clock, or NaN when the window cannot support one. */
    public double muxKiBPerSecond() {
        return muxBytesPerSecond / 1024.0;
    }

    /** How many PCR samples the current rate rests on, so a row can show the evidence beside the number. */
    public int muxSamples() {
        return pcrSamplesUsed;
    }

    /** Every packet handed to the evaluator, null packets included: parsedBytes counts them, total does not. */
    public long packetsSeen() {
        return parsedBytes / PACKET;
    }

    /**
     * Packets this evaluator did not count: PID 0x1FFF (padding) plus adaptation-only packets, which carry no payload to
     * evaluate but DO carry the clock. On the 2026-11-23 capture the split is 0.00 % null and 9.28 % adaptation-only.
     */
    public long nullPackets() {
        return packetsSeen() - total;
    }

    /** Total gaps counted across all PIDs. */
    public long gaps() {
        long sum = 0;
        for (int pid = 0; pid < MAX_PID; pid++) {
            sum += gapsPerPid[pid];
        }
        return sum;
    }

    /** Packets whose continuity counter repeated: complete but out of order, or repeated by the mux. */
    public long duplicates() {
        return duplicates;
    }

    public long packets() {
        return total;
    }

    public long uncleanPackets() {
        return unclean;
    }
}
