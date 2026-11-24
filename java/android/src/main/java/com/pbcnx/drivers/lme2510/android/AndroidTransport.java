/*
 * Copyright (c) 2026 The LME2510 Open Driver Authors.  MIT.
 *  Marker: lme2510-pbcnx.
 */
package com.pbcnx.drivers.lme2510.android;

import android.content.Context;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.util.Log;

import com.pbcnx.drivers.lme2510.Lme2510Device;
import com.pbcnx.drivers.lme2510.Transport;

/**
 * Android transport: {@code UsbManager} + bulk endpoints.
 *
 * <p>The vendor app opens the device by vendor id 0x3344 and hands the file
 * descriptor to its native SDK. This class keeps the connection in Java and does
 * the transfers directly, so the core above it is pure JVM.
 *
 * <p>Two details cost a compile and a session on hardware on the other platforms, so
 * they are handled the same way here: the data endpoints live on <em>alternate
 * setting 1</em>, not 0, and the endpoint constants are in {@link UsbConstants},
 * not on {@link UsbEndpoint}.
 */
public final class AndroidTransport implements Transport {

    private static final String TAG = "LME2510";
    private final java.util.concurrent.atomic.AtomicInteger readLogs =
            new java.util.concurrent.atomic.AtomicInteger();
    /** The vendor-shaped async queue; null means the blocking path is in use. */
    private AsyncTsPipe async;
    /** Set once the platform has refused the queue, so it is not retried on every read. */
    private boolean asyncRefused;
    /** How many queues this transport has started. More than one is a bug and the log will show it. */
    private int asyncInstances;
    private final java.util.concurrent.atomic.AtomicInteger haltClears =
            new java.util.concurrent.atomic.AtomicInteger();

    private static final int TIMEOUT_MS = 2000;

    private final UsbManager manager;
    private final UsbDevice device;

    private UsbDeviceConnection connection;
    private UsbInterface usbInterface;
    private UsbEndpoint endpointIn;
    private UsbEndpoint endpointOut;
    private UsbEndpoint tsEndpoint;

    public AndroidTransport(Context context, UsbDevice device) {
        this.manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        this.device = device;
    }

    /** First device on the bus that matches the LME ids, or null. */
    public static UsbDevice find(UsbManager manager) {
        for (UsbDevice device : manager.getDeviceList().values()) {
            if (Lme2510Device.accepts(device.getVendorId(), device.getProductId())) {
                return device;
            }
        }
        return null;
    }

    public UsbDevice device() {
        return device;
    }

    @Override
    public void open() {
        if (connection != null && isOpen()) {
            return;                          // already open: a second openDevice() would leak a UsbDeviceConnection
        }
        connection = manager.openDevice(device);
        if (connection == null) {
            throw new IllegalStateException("openDevice failed (permission?)");
        }
        usbInterface = pickInterface();
        if (usbInterface == null) {
            connection.close();
            connection = null;
            throw new IllegalStateException("no interface with bulk endpoints");
        }
        if (!connection.claimInterface(usbInterface, true)) {
            connection.close();
            connection = null;
            throw new IllegalStateException("claimInterface failed");
        }
        // SET_INTERFACE, and it is not optional: claimInterface does not change the
        // alternate setting on Android. Alt 0 of this device has no endpoints at all,
        // so without this every bulkTransfer answers -1 while the claim itself looks
        // fine. libusb does it for us on the desktop
        // (set_interface_altsetting(interface=0, alternate=1)); here it is explicit.
        if (!connection.setInterface(usbInterface)) {
            connection.releaseInterface(usbInterface);
            connection.close();
            connection = null;
            throw new IllegalStateException(
                    "setInterface(alt " + usbInterface.getAlternateSetting() + ") failed");
        }
        // Android 15 (and some kernels) DROP the claim when the alternate setting changes:
        // the kernel then rejects every transfer with
        //   usbfs: process <name> did not claim interface 0 before use
        // which is what the Xiaomi showed while the LEAP (Android 14) accepted the same
        // sequence. Claim again after setInterface; the second claim is a no-op when the
        // first one survived.
        if (!connection.claimInterface(usbInterface, true)) {
            connection.releaseInterface(usbInterface);
            connection.close();
            connection = null;
            throw new IllegalStateException("claimInterface after setInterface failed");
        }
        // Pick by address, not by "the last one in the list": alt 1 carries 0x81 and
        // 0x86 IN, 0x01 and 0x02 OUT, and the cold frames belong on 0x01/0x81. The
        // loop this replaced kept the last pair (0x86/0x02) and the first Android run
        // really did write to 0x02.
        for (int index = 0; index < usbInterface.getEndpointCount(); index++) {
            UsbEndpoint endpoint = usbInterface.getEndpoint(index);
            if (endpoint.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) {
                continue;
            }
            int address = endpoint.getAddress();
            if (endpoint.getDirection() == UsbConstants.USB_DIR_IN) {
                if (endpointIn == null || address == 0x81) {
                    endpointIn = endpoint;
                }
            } else if (endpointOut == null || address == 0x01) {
                endpointOut = endpoint;
            }
        }
        // MPEG-TS does NOT come on 0x81 (the command endpoint): it is 0x88 where the unit
        // has it, else the largest bulk IN that is not 0x81 -- 0x86 on this one. Reading TS
        // from 0x81 returns nothing, which is why the stream measured 0 B after a lock.
        for (int index = 0; index < usbInterface.getEndpointCount(); index++) {
            UsbEndpoint endpoint = usbInterface.getEndpoint(index);
            if (endpoint.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK
                    || endpoint.getDirection() != UsbConstants.USB_DIR_IN) {
                continue;
            }
            int address = endpoint.getAddress();
            if (address == 0x81) {
                continue;
            }
            if (address == 0x88 || tsEndpoint == null
                    || endpoint.getMaxPacketSize() > tsEndpoint.getMaxPacketSize()) {
                tsEndpoint = endpoint;
            }
        }
        // IT9175's question (2026-11-23): which pipe does the reader get, and was it resolved on the warm
        // device? Print the table the device actually exposes, then the three choices. A read on the wrong
        // endpoint times out with no error on Android, which is indistinguishable from a closed gate without
        // this line.
        StringBuilder table = new StringBuilder("endpoint table: ");
        for (int index = 0; index < usbInterface.getEndpointCount(); index++) {
            UsbEndpoint endpoint = usbInterface.getEndpoint(index);
            table.append(String.format("[0x%02x %s %s max %d] ",
                    endpoint.getAddress(),
                    endpoint.getDirection() == UsbConstants.USB_DIR_IN ? "in" : "out",
                    endpoint.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK ? "bulk" : "other",
                    endpoint.getMaxPacketSize()));
        }
        Log.i(TAG, table + "-> ts " + (tsEndpoint == null ? "--" : String.format("0x%02x",
                tsEndpoint.getAddress())) + ", in " + String.format("0x%02x", endpointIn == null ? 0
                : endpointIn.getAddress()) + ", out " + String.format("0x%02x", endpointOut == null ? 0
                : endpointOut.getAddress()) + " on " + device.getDeviceName() + " ("
                + Integer.toHexString(device.getProductId()) + ")");

        if (endpointIn == null || endpointOut == null) {
            connection.releaseInterface(usbInterface);
            connection.close();
            connection = null;
            throw new IllegalStateException("a bulk endpoint is missing");
        }
        // ARMED HERE, IN open(). Evidence for the timing, and its exact strength (the host rows; HEAD's reading 2026-11-23):
        //   f12ef626  armed in open()         streamed (196 completions, 10.58/s, 0 dropped)
        //   9a5e9d59  armed at the first read stalled (5 completions, demux 548 -> 0), race closed in both
        // That is ONE pair, in ONE order, on a band that was unstable all evening, and the E file does not contain the
        // row's headline. So the reading is LIKELY, not proven: the repeat pair with the order swapped is what would
        // make it a verdict. Until then this line stays here for the reason above and NOT because it is established -
        // if the repeat pair shows both builds streaming or both stalling, the cause is the band or the session order
        // and this comment is wrong.
        startAsyncPipe();
    }

    /**
     * Start the vendor-shaped queue: four 51,200-byte requests in flight on 0x88 with no timeout. A platform that
     * refuses leaves {@code async} null and every TS read falls back to the blocking path - a refusal must never
     * be worse than what shipped before.
     */
    private synchronized void startAsyncPipe() {
        if (async != null || asyncRefused) {
            // idempotent: one queue per transport. Three starts in one millisecond - which the host's log shows - would be
            // three queues and twelve URBs on one endpoint, the stacked-reader failure this package has already paid
            // for once.
            return;
        }
        UsbEndpoint pipe = tsEndpoint != null ? tsEndpoint : endpointIn;
        if (pipe == null || connection == null) {
            return;
        }
        // ONLY the endpoint the vendor uses. The first delivering row started a queue on 0x86 (the cold table's
        // choice) and then another on 0x88, so two queues existed on one device and the log could not tell which one
        // the bytes came from. The vendor's TS endpoint is 0x88 (measured 2026-11-23); anywhere else we keep the
        // blocking path and say so.
        if (pipe.getAddress() != 0x88) {
            Log.i(TAG, "async pipe not started: the TS endpoint resolved to 0x"
                    + Integer.toHexString(pipe.getAddress()) + ", and the vendor's is 0x88");
            return;
        }
        asyncInstances++;
        Log.i(TAG, "async pipe: instance " + asyncInstances + " on 0x"
                + Integer.toHexString(pipe.getAddress()) + (asyncInstances > 1 ? "  <-- MORE THAN ONE QUEUE" : ""));
        AsyncTsPipe candidate = new AsyncTsPipe(connection, pipe, asyncInstances);
        if (candidate.start()) {
            async = candidate;
        } else {
            asyncRefused = true;
        }
    }

    /**
     * The interface carrying the bulk pipes. The data endpoints are on alternate
     * setting 1; Android exposes each alternate setting as its own UsbInterface,
     * so pick by endpoints rather than by index.
     */
    private UsbInterface pickInterface() {
        for (int index = 0; index < device.getInterfaceCount(); index++) {
            UsbInterface candidate = device.getInterface(index);
            boolean hasIn = false;
            boolean hasOut = false;
            for (int endpointIndex = 0; endpointIndex < candidate.getEndpointCount();
                 endpointIndex++) {
                UsbEndpoint endpoint = candidate.getEndpoint(endpointIndex);
                if (endpoint.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    continue;
                }
                if (endpoint.getDirection() == UsbConstants.USB_DIR_IN) {
                    hasIn = true;
                } else {
                    hasOut = true;
                }
            }
            if (hasIn && hasOut) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * The async queue harvests one endpoint in completion order, so it IS the order. While it is live the reader
     * must use a single consumer - four threads would take sequence numbers for chunks they did not receive.
     */
    @Override
    public boolean orderedSource() {
        return async != null && async.isRunning();
    }

    /** Largest completion gap in the pipe's current reporting window. */
    @Override
    public long windowMaxGapMs() {
        return async == null ? 0 : async.windowMaxGapMs();
    }

    /** Largest gap between completions since arming, 0 when the queue never started. */
    @Override
    public long maxCompletionGapMs() {
        return async == null ? 0 : async.maxIntervalEver();
    }

    /** Completions that followed a gap longer than half a second. */
    @Override
    public int slowCompletions() {
        return async == null ? 0 : async.slowCompletions();
    }

    @Override
    public void close() {
        if (async != null) {
            async.stop();
            async = null;
        }
        if (connection != null) {
            if (usbInterface != null) {
                connection.releaseInterface(usbInterface);
            }
            connection.close();
            connection = null;
        }
    }

    @Override
    public int write(byte[] data) {
        if (connection == null || endpointOut == null) {
            throw new IllegalStateException("transport not open");
        }
        return connection.bulkTransfer(endpointOut, data, data.length, TIMEOUT_MS);
    }

    /** Command replies are at most 1 + count bytes; anything larger is TS. */
    private static final int COMMAND_REPLY_MAX = 256;
    /** The largest single TS request; a bigger one cannot complete inside TIMEOUT_MS below ~25 KiB/s. */
    private static final int TS_READ_MAX = 51200;
    /**
     * The TS read timeout, and it is NOT the control timeout. The vendor application's batch is 80 TS packets
     * (15,040 B) read with a 300 ms timeout - the same geometry this line's Python core records as
     * {@code TS_READ = TS_PACKET * 80} with {@code read_ts(size, timeout=300)} in {@code lme2510/transport.py:21,276},
     * and the geometry that streams on this hardware. Asking for 51,200 B with 2,000 ms (the migrated numbers) makes
     * every read wait for a batch the device was never asked for.
     */
    private static final int TS_TIMEOUT_MS = 500;
    /** How long a reader waits for a completed async URB before the blocking path is tried once. */
    private static final int TS_TAKE_TIMEOUT_MS = 400;


    @Override
    public byte[] read(int size) {
        if (connection == null || endpointIn == null) {
            throw new IllegalStateException("transport not open");
        }
        if (size > COMMAND_REPLY_MAX) {
            // TS: one transfer is one batch, and on its own endpoint.
            // A 51,200-byte request with a 2 s timeout needs >= 25 KiB/s to complete: on a marginal mux it CANNOT,
            // and every read returns -1 forever (the host measured 10 KiB/s => 0 B in 2.016 s windows, 2026-11-23). So one
            // request never asks for more than TS_READ_MAX: at 8 KiB a 2 s window survives down to 4 KiB/s, and the
            // caller's chunk size stays the same at the other end because TsReader aggregates.
            if (async != null && async.isRunning()) {
                // NEVER fall through to the blocking path while the queue is live: two mechanisms reading the same
                // endpoint is the stacked-reader failure this package already paid for once. A quiet window returns
                // empty, the reader loop asks again, and the self-healing gate decides whether the pipe needs help.
                byte[] fromQueue = async.take(TS_TAKE_TIMEOUT_MS);
                if (fromQueue != null) {
                    return fromQueue;
                }
                fromQueue = async.take(TS_TAKE_TIMEOUT_MS);
                return fromQueue != null ? fromQueue : new byte[0];
            }
            int want = Math.min(size, TS_READ_MAX);
            byte[] batch = new byte[want];
            UsbEndpoint pipe = tsEndpoint != null ? tsEndpoint : endpointIn;
            long readStarted = System.currentTimeMillis();
            int read = connection.bulkTransfer(pipe, batch, want, TS_TIMEOUT_MS);
            long readElapsed = System.currentTimeMillis() - readStarted;
            // The reader's line ("asked 51200, got 0 B") cannot tell a timeout from an error from a short read: only the
            // transport sees the return code. Log every read that is not a full one, bounded so a dead pipe cannot eat
            // the log budget: rc < 0 in ~2000 ms is a timeout, rc < 0 in ~0 ms is an error, 0 < rc < want is a short one.
            if (read != want && readLogs.incrementAndGet() <= 12) {
                Log.i(TAG, "ts read on 0x" + Integer.toHexString(pipe.getAddress()) + ": asked " + want
                        + ", rc " + read + ", " + readElapsed + " ms (timeout " + TS_TIMEOUT_MS + ", max "
                        + pipe.getMaxPacketSize() + " B)");
            }
            if (read < 0) {
                // Log it, and NOTHING ELSE. An earlier build sent CLEAR_FEATURE(ENDPOINT_HALT) on every failed read;
                // the Python core that streams at 565-914 KiB/s on this stick never clears a halt, and doing it per
                // failure adds a control transfer to a hot path and can reset the endpoint exactly when the mux is
                // marginal. A stalled pipe is recovered by reopening the device, not per read.
                if (readLogs.incrementAndGet() <= 12) {
                    Log.i(TAG, "ts read on 0x" + Integer.toHexString(pipe.getAddress()) + ": asked " + want
                            + ", rc " + read + ", " + readElapsed + " ms (timeout " + TS_TIMEOUT_MS + ", max "
                            + pipe.getMaxPacketSize() + " B)");
                }
            } else if (read != want && readLogs.incrementAndGet() <= 12) {
                Log.i(TAG, "ts read on 0x" + Integer.toHexString(pipe.getAddress()) + ": asked " + want
                        + ", rc " + read + ", " + readElapsed + " ms (short read)");
            }
            return trim(batch, read);
        }
        // A command reply: 0x55 plus the payload, which the device usually sends in
        // one packet but may split across packets. One bulkTransfer returns on the
        // first short packet, so a single call can hand back only the marker; the
        // rest then sits queued and every later read is shifted. That is exactly the
        // LEAP's "34, 49, -1, -1" (good reads, then desync). Accumulate instead.
        byte[] buffer = new byte[size];
        int got = 0;
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (got < size) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) {
                break;
            }
            int read = connection.bulkTransfer(endpointIn, buffer, got, size - got,
                    (int) Math.min(left, 200L));
            if (read > 0) {
                got += read;
            } else {
                break;                       // -1: nothing arrived in this window
            }
        }
        return trim(buffer, got);
    }

    private static byte[] trim(byte[] buffer, int length) {
        if (length <= 0) {
            return new byte[0];
        }
        if (length == buffer.length) {
            return buffer;
        }
        byte[] trimmed = new byte[length];
        System.arraycopy(buffer, 0, trimmed, 0, length);
        return trimmed;
    }

    @Override
    public int control(int requestType, int request, int value, int index, byte[] data) {
        if (connection == null) {
            throw new IllegalStateException("transport not open");
        }
        return connection.controlTransfer(requestType, request, value, index, data, data.length, TIMEOUT_MS);
    }

    @Override
    public boolean isOpen() {
        return connection != null;
    }
}
