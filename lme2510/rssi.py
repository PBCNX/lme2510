"""The received level the FC8300 will actually give, and the map we show it with.

Transcribed from `fc8300b_get_rssi` @ `0x1a1ec` in the vendor library: read tuner
register `0xEB` through the demod's `0x0F00` window (one byte, **signed**), and add 6 when
`0x44 & 0x7F <= 0x5F`. Both registers are read on the same path the tune and the scan
already use.

The result is a **relative** level, not dBm: measured on the LEAP 2026-11-16, a locked
8 Mbps mux read -49 and an empty channel -57, which is too narrow a spread for dBm — the
tuner's AGC is in the loop. So `percent()` is our own presentation map with two stated
anchors, not a calibrated scale.

**The cadence is the point.** A read while the stream runs costs most of the stream
(measured: 606 chunks in 30 s without reads, 20 with one read per second), so this module
is called at tune and at scan only, before `start_stream()`.
"""

RSSI = 0x0FEB
GATE = 0x0F44
GATE_LIMIT = 0x5F
CORRECTION = 6

#: Our map: the level that reads 0 %, and the level that reads 100 %.
EMPTY = -70
FULL = -35


def read(core):
    """The tuner's level, or ``None`` when the device answered nothing."""
    raw = core.read(RSSI, 1)
    if not raw:
        return None
    level = raw[0] - 256 if raw[0] > 0x7F else raw[0]
    gate = core.read(GATE, 1)
    if gate and (gate[0] & 0x7F) <= GATE_LIMIT:
        level += CORRECTION
    return level


def percent(level):
    """The level on our 0–100 map, clamped; ``None`` in, ``None`` out."""
    if level is None:
        return None
    span = FULL - EMPTY
    value = (level - EMPTY) * 100 // span
    return max(0, min(100, value))


def token(level):
    """What the status line carries: ``"62"``, or ``"n/a"`` when there is no reading."""
    value = percent(level)
    return "n/a" if value is None else str(value)
