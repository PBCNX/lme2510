/*
 * Copyright (c) 2026 The LME2510 Open Driver Authors.  MIT.
 *  Marker: lme2510-pbcnx.
 */
package com.pbcnx.drivers.lme2510;

/** One open LME2510C handle, independent of the platform that produced it. */
public interface Transport {

    void open();

    void close();

    /** Bulk write; returns the number of bytes accepted. */
    int write(byte[] data);

    /** Bulk read; returns up to {@code size} bytes (possibly empty on timeout). */
    byte[] read(int size);

    /**
     * A control transfer - the USB request a bulk read cannot make. The descriptor gate needs
     * {@code GET_DESCRIPTOR} for a string, which is how a stick says whether the descriptor table it was sent is the
     * one it is serving.
     *
     * @return bytes transferred, or a negative value when the device refused
     */
    int control(int requestType, int request, int value, int index, byte[] data);

    /**
     * True when {@link #read} draws from a single queue that is ALREADY in completion order.
     *
     * <p>A pooled reader assigns its sequence number before the read, which is right for four independent
     * transfers and wrong for one shared FIFO: reader A takes sequence 0 and reader B sequence 1, and if B's take
     * wins it receives chunk 0 - so the ordered writer emits chunk 1 then chunk 0. Measured 2026-11-23: 0.997 of
     * the bytes reached the demux with continuity errors of +100..+208 per 5 s, which is the signature of
     * reordering, not loss (HEAD's reading). With one consumer the queue's own order is preserved.</p>
     */
    default boolean orderedSource() {
        return false;
    }

    /**
     * Whether this transport can perform {@link #control}. A test fake overrides this to {@code false} so a gate that
     * needs the device does not accuse it of lying - the alternative is a fake that returns an empty descriptor and a
     * driver that cannot tell "no answer" from "wrong answer".
     */
    /** Largest completion gap since the source's own last report, when it can report one; 0 otherwise. */
    default long windowMaxGapMs() {
        return 0;
    }

    /** Largest gap between two completions, when the source can report one; 0 otherwise. */
    default long maxCompletionGapMs() {
        return 0;
    }

    /** Completions that followed a stall longer than half a second, when the source can report them; 0 otherwise. */
    default int slowCompletions() {
        return 0;
    }

    default boolean supportsControl() {
        return true;
    }

    boolean isOpen();
}
