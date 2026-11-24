"""The vendor's lock check, transcribed: `fc8300_scan_status` (0x17830).

This is the function `mtv_lock_check` calls, and `LYDEV_Tuner_Setfreq` calls that
up to four times.  It is **not** a byte compare: it walks a bit machine over
`0x3025`, `0x3026`, `0x2023` and `0x50c5` with 10 ms waits, and its return value
(taken from the last poll, `0x50c5`) is what gates the stream.

Read out of the vendor application, the shifts matter:

    abStack_25[0] & 1          -> bit 0
    abStack_25[0] & 8          -> bit 3
    -1 < (x << 0x1d)           -> bit 2   (29: sign bit = bit 2)
    -1 < (x << 0x19)           -> bit 6   (25: sign bit = bit 6)
    (x << 0x1b) < 0            -> bit 4   (27)
    (x << 0x1e) < 0            -> bit 1   (30)

Bit 7 is never tested -- the "lock byte 0xff" that the notes carried for a day
was incidental.  The vendor's own locking run ends on `0x3025 = 0x5f`.

The transport is anything with `read(register) -> int | None` (None = no payload).
`wait` is injected so tests do not sleep.
"""
import time

LOCK_REGISTER = 0x3025
STATUS_REGISTER = 0x3026
AUX_REGISTER = 0x2023
DATA_REGISTER = 0x50C5

WAIT = 0.010                      # the vendor's msWait(10) between polls
LOCK_BITS = (0x01, 0x08, 0x04, 0x40)   # bit0, bit3, bit2, bit6: the order tested


def _vendor_wait():
    """The poll cadence the vendor uses: `WAIT` between lock reads.

    Measured on the hardware 2026-11-19: 3 ms locked 0/10,
    5 ms 1/10, **10 ms 10/10**.  The cadence matters because the demod moves one
    bit per poll; polling faster than it settles reads the same byte twice and the
    scan gives up on a mux that would have locked.
    """
    time.sleep(WAIT)


def _bit(value, mask):
    return bool(value & mask)


def scan(transport, band=4, wait=None):
    """Run the vendor's scan.  Returns ``(locked, trace)``.

    ``trace`` is a list of ``(register, value, step)`` so a caller can see where
    it stopped; the caller should treat ``locked`` as the vendor does -- a lock is
    necessary, and the stream is still the final verdict.

    ``wait`` is the *poll* pacing, and it defaults to the vendor's 10 ms -- tests
    pass ``wait=lambda: None``.  It used to default to ``time.sleep``, which is a
    TypeError waiting to happen, and callers that passed their own 1 ms pacing
    made the scan a coin flip (2026-11-19: 4 of 7 runs locked at 1 ms, 10 of 10 at
    10 ms).
    """
    if wait is None:
        wait = _vendor_wait
    log = []

    def read(register):
        value = transport.read(register)
        log.append((register, value, len(log)))
        return value

    def poll(register, mask, expected, tries, step):
        """Read until ``value & mask`` matches, up to ``tries`` times."""
        for _ in range(tries):
            value = read(register)
            if value is not None and (value & mask) == expected:
                return True
            wait()
        return False

    value = read(LOCK_REGISTER)
    if value is None:
        return False, log
    if not _bit(value, LOCK_BITS[0]):
        matched = False
        for _ in range(7):
            wait()
            value = read(LOCK_REGISTER)
            if value is not None and _bit(value, LOCK_BITS[0]):
                matched = True
                break
        if not matched:
            return False, log
    if not poll(LOCK_REGISTER, LOCK_BITS[1], LOCK_BITS[1], 16, "bit3"):
        return False, log
    value = read(LOCK_REGISTER)
    if value is None or not _bit(value, LOCK_BITS[2]):
        return False, log
    if not poll(STATUS_REGISTER, 0x11, 0x11, 10, "3026 bits 0+4"):
        return False, log
    if not poll(LOCK_REGISTER, LOCK_BITS[3], LOCK_BITS[3], 12, "bit6"):
        return False, log
    value = read(AUX_REGISTER)
    if value is None or _bit(value, 0x01):
        return False, log
    if not poll(STATUS_REGISTER, 0x02, 0x02, 105, "3026 bit1"):
        return False, log
    # the per-band switch; band 4 (ours) goes to the default path
    if band >= 8:
        passes = 0
        while passes < 95:
            value = read(DATA_REGISTER)
            if value:
                return True, log
            wait()
            passes += 1
        return False, log
    return _band_check(transport, band, read, wait, log)


def _band_check(transport, band, read, wait, log):
    """The `switch (band)` arm.

    The arms differ per band and the jump table was not recovered, so
    this arm is deliberately honest: it reads the same registers the switch reads
    and requires the one condition every arm shares -- `0x50c5` non-zero, the
    vendor's "the demod has data" byte.
    """
    for _ in range(95):
        value = read(DATA_REGISTER)
        if value:
            return True, log
        wait()
    return False, log


def verdict(trace):
    """A one-line reading of a scan trace, for logs."""
    if not trace:
        return "no reads"
    register, value, _ = trace[-1]
    shown = "0x%02x" % value if value is not None else "--"
    return "last read 0x%04x = %s after %d read(s)" % (register, shown, len(trace))
