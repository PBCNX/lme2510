/*
 * Copyright (c) 2026 The LME2510 Open Driver Authors.  MIT.
 *  Marker: lme2510-pbcnx.
 */
package com.pbcnx.drivers.lme2510;

/**
 * Finds the 188-byte packet phase and emits only whole packets.
 *
 * <p>A USB bulk transfer returns whatever length the device ended the transfer with, and the vendor's 51,200-byte URB is
 * not a multiple of 188 (272.34 packets), so a host that parses from offset 0 never recovers. This is the same rule as the
 * Python core's {@code PacketAligner}, and it belongs in the <b>writer</b> - the one place chunks are in sequence order -
 * not in the transport, where concurrent reads complete out of order. Measured 2026-11-23: per-read alignment on the
 * Python side gave two seams and 14 continuity errors in a 30 s capture; alignment in the ordered writer gave 100 % of
 * packets starting at 0x47.</p>
 */
public final class PacketAligner {

    private static final int PACKET = 188;
    private static final byte[] NOTHING = new byte[0];

    private byte[] buffer = new byte[PACKET * 8];
    private int length;
    /**
     * Bytes thrown away: leading garbage before the first 0x47, and a run whose phase did not hold. This is the
     * only place between the USB harvest and the sink where this package can lose packets, and until it was
     * counted it was invisible (the host, 2026-11-23: pump 0 dropped, demux 1.9 % gaps, and the boundary between them
     * uncovered by either counter).
     */
    private long discarded;

    /** Feed raw bytes, take back whole packets (a zero-length array while no whole packet is complete). */
    public byte[] push(byte[] data) {
        if (data == null || data.length == 0) {
            return NOTHING;
        }
        if (length + data.length > buffer.length) {
            byte[] grown = new byte[Math.max(buffer.length * 2, length + data.length)];
            System.arraycopy(buffer, 0, grown, 0, length);
            buffer = grown;
        }
        System.arraycopy(data, 0, buffer, length, data.length);
        length += data.length;
        return emit();
    }

    private byte[] emit() {
        int offset = 0;
        while (offset < PACKET) {
            int left = length - offset;
            if (left >= 2 * PACKET) {
                if (buffer[offset] == 0x47 && buffer[offset + PACKET] == 0x47) {
                    break;
                }
                offset++;
            } else if (left >= PACKET) {
                if (buffer[offset] == 0x47) {
                    break;
                }
                offset++;
            } else {
                drop(offset);
                return NOTHING;
            }
        }
        if (offset >= PACKET) {
            // a whole packet's worth of bytes with no phase in them: keep only the tail that could still hold one
            int keep = Math.min(2 * PACKET, length);
            drop(length - keep);
            return NOTHING;
        }
        int count = (length - offset) / PACKET * PACKET;
        if (count == 0) {
            drop(offset);
            return NOTHING;
        }
        for (int start = offset; start < offset + count; start += PACKET) {
            if (buffer[start] != 0x47) {
                // the phase did not hold across the run: discard the run rather than emitting a broken one
                discarded += count;
                length = 0;
                return NOTHING;
            }
        }
        byte[] packets = new byte[count];
        System.arraycopy(buffer, offset, packets, 0, count);
        drop(offset + count);
        return packets;
    }

    /** Remove the first {@code count} bytes from the buffer. */
    private void drop(int count) {
        if (count <= 0) {
            return;
        }
        int remaining = length - count;
        if (remaining <= 0) {
            length = 0;
            return;
        }
        System.arraycopy(buffer, count, buffer, 0, remaining);
        length = remaining;
    }

    /** Bytes thrown away since this aligner was created. */
    public long discarded() {
        return discarded;
    }

    /** Bytes currently held back: a partial packet, or bytes waiting for a phase. */
    public int held() {
        return length;
    }
}
