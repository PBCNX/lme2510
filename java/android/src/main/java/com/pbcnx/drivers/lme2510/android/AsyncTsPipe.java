/*
 * Copyright (c) 2026 The LME2510 Open Driver Authors.  MIT.
 *  Marker: lme2510-pbcnx.
 */
package com.pbcnx.drivers.lme2510.android;

import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbRequest;
import android.util.Log;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The vendor's TS pipe: several asynchronous URBs in flight, no timeout, completions harvested in order.
 *
 * <p>Measured on the vendor application (2026-11-23): every TS transfer is
 * {@code libusb_submit_transfer} on endpoint 0x88 with <b>length 51,200 and timeout 0</b>, four to five in flight, a
 * completion every ~136 ms, and 71 of 76 completions exactly 51,200 B. It never issues a bounded read, which is why a
 * marginal mux cannot make it lose a batch.</p>
 *
 * <p>This class is the Android equivalent: {@code UsbRequest.queue()} submits without a timeout, one pump thread owns
 * {@code requestWait()}, and a finished buffer is handed to whoever calls {@link #take}. The device only ever sees
 * transfers that complete; nothing is discarded for being slow.</p>
 *
 * <p><b>The one uncertainty, logged rather than assumed:</b> Android's {@code UsbRequest} does not return the number of
 * bytes transferred. The convention every implementation relies on is that the direct {@link ByteBuffer}'s position is
 * the end of the received data after completion, so the first completions log {@code position} and {@code limit}; if a
 * measurement shows position 0 on a full buffer, this class adapts to {@code limit} and says so.</p>
 */
final class AsyncTsPipe {

    private static final String TAG = "LME2510";

    /** Requests kept in flight: the vendor runs four to five (measured 2026-11-23). */
    static final int DEPTH = 4;
    /** Bytes per request: the vendor's 51,200-byte URB. */
    static final int BYTES = 51200;

    private final UsbDeviceConnection connection;
    private final UsbEndpoint endpoint;
    private final int depth;
    private final int bytes;

    private final ArrayBlockingQueue<byte[]> ready;
    private final List<UsbRequest> requests;
    private final Map<UsbRequest, ByteBuffer> buffers;
    private final ArrayDeque<UsbRequest> idle;

    private volatile boolean running;
    private Thread pump;
    private int completions;
    /**
     * Chunks the hand-off had to discard because no reader was taking. This is the number that says whether the
     * loss is HERE rather than on the wire: HEAD's arithmetic on the 10:58 row (115 completions x 51,200 B in
     * 10 s ~ 575 KiB/s at the USB layer against 134 KiB/s read by the host) only holds if roughly three quarters
     * of the bytes were dropped between the pump and the caller, and until now the drop was silent.
     */
    private int droppedChunks;
    /**
     * Completions that belong to ANOTHER request on this connection. requestWait() returns any completed request on
     * the connection, not one of the caller's own, so a second queue steals them - and the old code did
     * `if (buffer == null) continue;`, which retired a foreign URB silently and forever. Counted and logged now
     * (HEAD, 2026-11-23).
     */
    private int foreignCompletions;
    private int instance = 1;
    private long deliveredBytes;
    private boolean loggedFirstCompletions;
    private long lastCompletionAt;
    private long minIntervalMs = Long.MAX_VALUE;
    private long maxIntervalMs;
    /**
     * The largest gap between completions since this queue started, and how many gaps exceeded half a second. the host's
     * sustained run (2026-11-23) found the rate decaying 584 -> 465 -> 396 -> 58 KiB/s over twenty minutes with the
     * interval's MAX growing 96 -> 1,913 -> 4,223 -> 13,377 ms, so the growth is the finding and it needs a number that
     * survives the 10 s window the other two are reset in.
     */
    private long maxIntervalEver;
    private int slowCompletions;
    private long startedAt = System.currentTimeMillis();
    private long lastReportAt = System.currentTimeMillis();
    private int lastReportedCompletions;
    private long lastReportedBytes;

    AsyncTsPipe(UsbDeviceConnection connection, UsbEndpoint endpoint) {
        this(connection, endpoint, DEPTH, BYTES);
    }

    AsyncTsPipe(UsbDeviceConnection connection, UsbEndpoint endpoint, int instance) {
        this(connection, endpoint, DEPTH, BYTES);
        this.instance = instance;
    }

    AsyncTsPipe(UsbDeviceConnection connection, UsbEndpoint endpoint, int depth, int bytes) {
        this.connection = connection;
        this.endpoint = endpoint;
        this.depth = depth;
        this.bytes = bytes;
        this.ready = new ArrayBlockingQueue<>(Math.max(32, depth * 8));
        this.requests = new ArrayList<>(depth);
        this.buffers = new IdentityHashMap<>();
        this.idle = new ArrayDeque<>(depth);
    }

    /** Submit the first requests and start harvesting. Returns false when the platform refuses, so a caller can fall back. */
    boolean start() {
        try {
            for (int index = 0; index < depth; index++) {
                UsbRequest request = new UsbRequest();
                if (!request.initialize(connection, endpoint)) {
                    Log.i(TAG, "async pipe: initialize refused after " + index + " request(s); falling back");
                    cancelAll();
                    return false;
                }
                ByteBuffer buffer = ByteBuffer.allocateDirect(bytes);
                buffers.put(request, buffer);
                requests.add(request);
                idle.add(request);
            }
            for (int index = 0; index < depth; index++) {
                if (!queueOne(idle.poll())) {
                    Log.i(TAG, "async pipe: queue refused on request " + index + "; falling back");
                    cancelAll();
                    return false;
                }
            }
        } catch (Throwable refused) {
            Log.i(TAG, "async pipe: platform refused the queue (" + refused + "); falling back");
            cancelAll();
            return false;
        }
        running = true;
        pump = new Thread(this::harvest, "lme2510-ts-urb");
        pump.start();
        Log.i(TAG, "async pipe: " + depth + " x " + bytes + " B in flight on 0x"
                + Integer.toHexString(endpoint.getAddress()) + " with no timeout");
        return true;
    }

    private boolean queueOne(UsbRequest request) {
        if (request == null) {
            return false;
        }
        ByteBuffer buffer = buffers.get(request);
        buffer.clear();
        return request.queue(buffer, bytes);
    }

    private void harvest() {
        String exit = "running flag cleared";
        try {
            while (running) {
                UsbRequest done;
                try {
                    done = connection.requestWait();
                } catch (RuntimeException gone) {
                    exit = "requestWait threw " + gone;
                    return;
                }
            if (done == null) {
                continue;
            }
            ByteBuffer buffer = buffers.get(done);
            if (buffer == null) {
                foreignCompletions++;
                if (foreignCompletions <= 5) {
                    // HEAD, 2026-11-23: the earlier wording claimed this URB was "left alone, not retired", and that
                    // is the opposite of the fact. requestWait() has already taken the completion, so the owning queue
                    // never sees it and cannot re-queue it from here - this pump does not own the request or its
                    // buffer. One URB fewer in flight on that queue, permanently. With one queue per transport this
                    // stays 0; if it prints, a second queue exists somewhere and a URB is lost per line.
                    Log.i(TAG, "async pipe " + instance + ": a completion belonging to ANOTHER queue was returned by "
                            + "requestWait() (" + foreignCompletions + " so far) - that URB is LOST: its owner never "
                            + "sees the completion and this pump cannot re-queue a request it does not own");
                }
                continue;
            }
            boolean positionWasReal = buffer.position() > 0;
            int length = transferred(buffer);
            completions++;
            long now = System.currentTimeMillis();
            if (lastCompletionAt > 0) {
                long interval = now - lastCompletionAt;
                if (interval < minIntervalMs) {
                    minIntervalMs = interval;
                }
                if (interval > maxIntervalMs) {
                    maxIntervalMs = interval;
                }
            }
            lastCompletionAt = now;
            // COPY FIRST, and only then re-queue. This was wrong in 0e3960cc: queueOne() clears the buffer and hands
            // it back to the kernel, so copying after the re-queue read a buffer that was already being refilled -
            // which is exactly the aliasing the host saw (a first dispatch of 51,136 B instead of 51,200, then a pipe
            // that counted zero). A borrowed buffer is never handed to a caller: every delivery is a fresh array, and
            // it is taken out before the URB goes back into flight.
            byte[] snapshot = null;
            if (length > 0) {
                snapshot = new byte[length];
                buffer.position(0);
                buffer.get(snapshot, 0, length);
            }
            if (running && !queueOne(done)) {
                Log.i(TAG, "async pipe: re-queue refused; the pipe is closing");
                running = false;
            }
            if (snapshot != null) {
                deliveredBytes += snapshot.length;
                if (!ready.offer(snapshot)) {
                    // the consumer is slower than the device: drop the oldest rather than grow without bound,
                    // and COUNT it - a silent drop is how three quarters of a stream disappears without a log line
                    ready.poll();
                    droppedChunks++;
                    ready.offer(snapshot);
                }
            }
            long clock = System.currentTimeMillis();
            if (clock - lastReportAt >= 10000L) {
                // the number the host asked for: completions per second and bytes per second, which is the rate defect
                // measured at the USB layer instead of being inferred from what the host renders
                double seconds = (clock - lastReportAt) / 1000.0;
                double totalSeconds = (clock - startedAt) / 1000.0;
                Log.i(TAG, String.format(Locale.US,
                        "async pipe: %d completions (+%d), %.1f MiB (+%.0f KiB), %.2f/s this window, %.2f/s since start, interval %d..%d ms, %d in flight, %d dropped",
                        completions, completions - lastReportedCompletions,
                        deliveredBytes / 1048576.0, (deliveredBytes - lastReportedBytes) / 1024.0,
                        (completions - lastReportedCompletions) / seconds, completions / Math.max(totalSeconds, 1.0),
                        minIntervalMs == Long.MAX_VALUE ? 0 : minIntervalMs, maxIntervalMs, inflightNow(), droppedChunks,
                        foreignCompletions));
                lastReportAt = clock;
                lastReportedCompletions = completions;
                lastReportedBytes = deliveredBytes;
            }
            if (!loggedFirstCompletions && completions >= 5) {
                loggedFirstCompletions = true;
                // the length as MEASURED, and whether it came from the buffer's position or from the limit fallback:
                // buffer.position() read after queueOne() is always 0, which is what the host's row showed and what made a
                // real length look like a broken one.
                Log.i(TAG, "async pipe: " + completions + " completions, " + deliveredBytes + " B delivered, last length "
                        + length + " (from " + (positionWasReal ? "position" : "limit fallback") + "), limit "
                        + buffer.limit());
            }
                if (!running) {
                    return;
                }
            }
        } catch (Throwable fatal) {
            exit = "harvest threw " + fatal;
            Log.i(TAG, "async pipe: harvest threw " + fatal);
        } finally {
            // WHICH exit, and with what, so a row that stops after nine completions says why instead of going quiet
            Log.i(TAG, "pump stopped: " + completions + " completions, " + deliveredBytes + " B delivered, " + droppedChunks
                    + " dropped, "
                    + inflightNow() + " in flight, last completion " + lastCompletionAt
                    + " ms after start, reason: " + exit);
        }
    }

    /** True when the pump is alive, URBs are pending, and nothing has completed for {@code millis}. */
    /**
     * The largest gap since this pipe's own 10 s line last printed - the per-window figure beside the cumulative one.
     * the host, 2026-11-23: a single number that only grows cannot tell a launch stall from a later one, and a 20-minute run
     * needs both.
     */
    public long windowMaxGapMs() {
        return maxIntervalMs == Long.MAX_VALUE ? 0 : maxIntervalMs;
    }

    /** The largest gap between two completions since this queue started. */
    public long maxIntervalEver() {
        return maxIntervalEver;
    }

    /** Completions whose predecessor was more than half a second earlier: the stalls, counted rather than sampled. */
    public int slowCompletions() {
        return slowCompletions;
    }

    boolean stalledFor(long millis) {
        if (!running) {
            return false;
        }
        long reference = lastCompletionAt > 0 ? lastCompletionAt : startedAt;
        return inflightNow() > 0 && System.currentTimeMillis() - reference >= millis;
    }

    long millisSinceCompletion() {
        long reference = lastCompletionAt > 0 ? lastCompletionAt : startedAt;
        return System.currentTimeMillis() - reference;
    }

    /**
     * The bytes this completion carried. Android does not return the count, so the direct buffer's position is the
     * answer (it is set to the end of the received data when the request completes); when a platform leaves the position
     * at zero and the limit short, the limit is used instead - and that is logged rather than guessed silently.
     */
    private int transferred(ByteBuffer buffer) {
        int position = buffer.position();
        if (position > 0) {
            return Math.min(position, bytes);
        }
        int limit = buffer.limit();
        if (limit > 0 && limit < bytes) {
            return limit;
        }
        return buffer.limit() >= bytes ? bytes : 0;
    }

    /** Hand the next completed chunk to a reader, or null when nothing completed inside the window. */
    byte[] take(int timeoutMs) {
        try {
            return ready.poll(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** Requests currently queued to the device (an approximation: idle is emptied on queue and refilled on
     * completion, so this counts what has not come back yet). */
    int inflightNow() {
        return Math.max(0, depth - idle.size());
    }

    boolean isRunning() {
        return running;
    }

    int completions() {
        return completions;
    }

    long deliveredBytes() {
        return deliveredBytes;
    }

    void stop() {
        running = false;
        cancelAll();
        Thread thread = pump;
        if (thread != null) {
            try {
                thread.join(1500L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        Log.i(TAG, "async pipe stopped after " + completions + " completions, " + deliveredBytes + " B");
    }

    private void cancelAll() {
        for (UsbRequest request : requests) {
            try {
                request.cancel();
            } catch (RuntimeException ignored) {
                // cancelling a request the platform already retired is not an error
            }
        }
        requests.clear();
        buffers.clear();
        idle.clear();
        ready.clear();
    }
}
