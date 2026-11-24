# Copyright (c) 2026 The LME2510 Open Driver Authors.  MIT.
# Marker: lme2510-pbcnx.
"""TMCC as this chip exposes it: the (184,102) codeword at 0x4110, LSB-first.

The LME2510C hands the host the raw TMCC block the demod receives: **23 bytes at `0x4110`-`0x4126`**,
which is the shortened (184,102) block STD-B31 protects TMCC with. The alignment is fixed by the
hardware, not guessed: the only bit that moves when the EWBS flag changes is `0x04` in `0x4113`, and
with `0x4110` as B0 read LSB-first that is exactly **bit 26**, where the standard puts startup control.

Verified against transmissions whose parameters were set by hand (2026-11-20):

    1+12, layer A QPSK 1 seg, layer B QPSK 12 seg, partial on, flag on   ->  ewbs 1 partial 1
                                                                             A QPSK 1 seg, B QPSK 12 seg
    1+12, layer A QPSK 1 seg, layer B 16QAM 12 seg, partial on, flag off ->  B 16QAM, layers again

**Not in TMCC:** mode and guard interval. STD-B31 has no field for them (carried in the IIP, not in TMCC, and not visible on this front end today), so a
`tmcc` reading here reports EWBS, partial reception and the layers, and says nothing about mode or GI.

The code-rate table is the standard's 3-bit value read as an index into 1/2, 2/3, 3/4, 5/6, 7/8; that
mapping is what makes the field agree with what was transmitted (`2` at 3/4, `3` at 5/6).
"""
import time

BASE = 0x4110
BLOCK = 23              # bytes: 184 bits
BIT_LENGTH = BLOCK * 8

MODULATION = {0: "DQPSK", 1: "QPSK", 2: "16QAM", 3: "64QAM"}
CODE_RATE = {0: "1/2", 1: "2/3", 2: "3/4", 3: "5/6", 4: "7/8"}
INTERLEAVE = {0: "I0", 1: "I1", 2: "I2", 3: "I4"}
UNUSED_LAYER = 15

#: How long two TMCC reads wait between each other before they count as "settled"
#: (the window IT9308 measured at 0.5 s and IT9175 at 800 ms, 2026-11-20).
SETTLE_SECONDS = 0.5

BIT_EWBS = 26
BIT_PARTIAL = 27
LAYER_BASES = {"a": 28, "b": 41, "c": 54}


def bits_of(registers, base=BASE, length=BIT_LENGTH):
    """The codeword as a bit list: byte by byte, **LSB first** within each byte."""
    bits = []
    for offset in range(length // 8 + 1):
        value = registers.get("0x%04x" % (base + offset)) or 0
        bits.extend((value >> shift) & 1 for shift in range(0, 8))
    return bits[:length]


def field(bits, first, length):
    """A field as an integer, first bit most significant (the standard's bit order)."""
    value = 0
    for position in range(first, first + length):
        value = (value << 1) | bits[position]
    return value


def layer(bits, base):
    segments = field(bits, base + 9, 4)
    return {
        "modulation": MODULATION.get(field(bits, base, 3), "?"),
        "code_rate": CODE_RATE.get(field(bits, base + 3, 3), "?"),
        "interleave": INTERLEAVE.get(field(bits, base + 6, 3), "?"),
        "segments": segments,
        "present": segments != UNUSED_LAYER,
    }


def decode(registers):
    """The TMCC fields this chip can report, from one block read.

    Refuses a block that cannot be a codeword. Measured 2026-11-20: on an unlocked channel the 23 registers
    all read `0x00`, and decoding that produced a confident, entirely invented reading (`DQPSK 1/2 I0
    0 seg` for all three layers). A field that is made up is worse than no field — the same rule as
    `lock_watch.judge()` and `f000_diff.comparable()`.
    """
    values = list(registers.values()) if isinstance(registers, dict) else list(registers)
    if len(values) < BLOCK:
        raise ValueError("TMCC block is %d bytes, not %d" % (len(values), BLOCK))
    if any(value is None for value in values):
        raise ValueError("TMCC block has unread registers: the demod did not answer")
    if not any(values):
        raise ValueError("TMCC block is all zero: the demod has not decoded a codeword "
                         "(no lock, or the channel has nothing to lock on)")
    bits = bits_of(registers)
    return {
        "ewbs": bool(field(bits, BIT_EWBS, 1)),
        "partial": bool(field(bits, BIT_PARTIAL, 1)),
        "layer_a": layer(bits, LAYER_BASES["a"]),
        "layer_b": layer(bits, LAYER_BASES["b"]),
        "layer_c": layer(bits, LAYER_BASES["c"]),
        "bits": "".join(str(bit) for bit in bits[:64]),
    }


def read(core):
    """Read the block through a driver `core` (anything with ``read(register, count)``)."""
    registers = {}
    for offset in range(BLOCK):
        address = BASE + offset
        data = core.read(address, 1)
        registers["0x%04x" % address] = data[0] if data else None
    return registers


def read_settled(core, settle=SETTLE_SECONDS, tries=6):
    """Read the codeword until **two consecutive reads agree**, then return it.

    Both IT9308 and IT9175 measured the same thing on their chips (2026-11-20): for a few hundred
    milliseconds after a switch the block is **transient**, and a stale block can pass the 1-13 segment
    gate while describing the *previous* multiplex. "Valid" is not "settled" - so a diagnostic command
    that shows a channel's parameters must not read once and believe it.

    Returns the settled register dict; raises ValueError when the block never stops moving.
    """
    previous = read(core)
    for _ in range(tries):
        time.sleep(settle)
        current = read(core)
        if current == previous:
            return current
        previous = current
    raise ValueError("the TMCC block did not settle after %d reads %.1f s apart"
                     % (tries + 1, settle))


def layer_text(name, info):
    if not info["present"]:
        return "%s: unused" % name
    return "%s: %s %s %s %d seg" % (name, info["modulation"], info["code_rate"],
                                    info["interleave"], info["segments"])


def segments_total(tmcc):
    """How many segments the codeword claims, counting only layers that are present."""
    total = 0
    for name in ("layer_a", "layer_b", "layer_c"):
        info = tmcc.get(name) or {}
        if info.get("present"):
            total += int(info.get("segments") or 0)
    return total


def valid(tmcc):
    """A reading is a channel only when a layer claims segments (1..13).

    IT9175's zero trap, 2026-11-20: a register the demod has not decoded yet reads 0, and that decodes
    into a plausible-looking picture with no layers - which an operator cannot tell from a real 2K /
    1/32 multiplex. A codeword that claims no segments is not a multiplex, and mode and guard are not
    ours to add (they ride in the IIP), so the segments are the only thing that can corroborate the
    reading. Same rule as `decode`'s all-zero refusal, one level up: refuse to *present* it too.
    """
    return 1 <= segments_total(tmcc) <= 13


def describe(tmcc):
    """One line, in the shape the other lines print: `ewbs 1  partial 0  A: …  B: …  C: …`."""
    return "ewbs %d  partial %d  %s  %s  %s" % (
        int(tmcc["ewbs"]), int(tmcc["partial"]),
        layer_text("A", tmcc["layer_a"]), layer_text("B", tmcc["layer_b"]),
        layer_text("C", tmcc["layer_c"]))
