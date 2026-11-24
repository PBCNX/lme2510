# Copyright (c) 2026 The LME2510 Open Driver Authors.  MIT.
# Marker: lme2510-pbcnx.
"""IIP — the ISDB-T Information Packet, where this front end's mode and guard live.

One TSP per multiplex frame, multiplexed in the *invalid hierarchy* (`layer_indicator` '1000') and carried
by none of layers A/B/C. Per ARIB STD-B31 v2.2-E1 §5.5.3 (Tables 5-8, 5-10) the 184-byte payload is:

    IIP_packet_pointer                              16 bits
    modulation_control_configuration_information   160 bits
    IIP_branch_number                                8 bits
    last_IIP_branch_number                           8 bits
    network_synchronization_information_length       8 bits
    network_synchronization_information()           ... then 0xFF stuffing

and it describes the multiplex frame **after** the one carrying it. Unlike TMCC it carries
`mode_GI_information` (current and next mode/guard), so a receiver that can see IIP knows mode and guard —
which TMCC does not carry at all.

Structure ported from IT9308's `it9308/iip.py` (2026-11-20), whose decode was validated against 131 IIP
packets in a Standards-library recording; the bit positions are theirs, the code is this line's. Our own
muxes carry no IIP, so this is exercised on the 204-byte recordings in the owner's `Transport Streams`
folder.
"""
MODES = {1: "2K", 2: "4K", 3: "8K"}
GUARDS = {0: "1/32", 1: "1/16", 2: "1/8", 3: "1/4"}
MODULATIONS = {0: "DQPSK", 1: "QPSK", 2: "16QAM", 3: "64QAM"}
CODES = {0: "1/2", 1: "2/3", 2: "3/4", 3: "5/6", 4: "7/8"}
INTERLEAVES = {0: "I0", 1: "I1", 2: "I2", 3: "I4"}
UNUSED = 7
UNKNOWN = "?"
IIP_PID = 0x1FF0


def crc32_mpeg2(data):
    """ISO/IEC 13818-1 CRC-32: poly 0x04C11DB7, init 0xFFFFFFFF, no reflection, no final XOR.

    STD-B31 Table 5-10's `CRC_32` covers the MCCI from `TMCC_synchronization_word` through
    `reserved_future_use` - the first 128 of the 160 bits - and is stored big-endian.
    """
    crc = 0xFFFFFFFF
    for byte in data:
        crc ^= byte << 24
        for _ in range(8):
            if crc & 0x80000000:
                crc = ((crc << 1) ^ 0x04C11DB7) & 0xFFFFFFFF
            else:
                crc = (crc << 1) & 0xFFFFFFFF
    return crc


def crc_ok(payload):
    """True when the packet's own CRC agrees with its first 128 MCCI bits."""
    covered = payload[2:18]
    stored = payload[18:22]
    return crc32_mpeg2(covered) == int.from_bytes(stored, "big")


class BitReader:
    """MSB-first bit reader over a byte string."""

    def __init__(self, data):
        self.bits = []
        for byte in data:
            for shift in range(7, -1, -1):
                self.bits.append((byte >> shift) & 1)
        self.position = 0

    def take(self, count):
        value = 0
        for _ in range(count):
            value = (value << 1) | self.bits[self.position]
            self.position += 1
        return value


def _layer(reader):
    modulation = reader.take(3)
    code = reader.take(3)
    interleave = reader.take(3)
    segments = reader.take(4)
    layer = {"present": modulation != UNUSED and segments != 15, "segments": segments,
             "modulation": UNKNOWN, "code_rate": UNKNOWN, "interleave": UNKNOWN}
    if layer["present"]:
        layer["modulation"] = MODULATIONS.get(modulation, UNKNOWN)
        layer["code_rate"] = CODES.get(code, UNKNOWN)
        layer["interleave"] = INTERLEAVES.get(interleave, UNKNOWN)
    return layer


def decode(payload):
    """One IIP payload: the 184 bytes after the 4-byte TSP header."""
    if len(payload) < 25:
        raise ValueError("IIP payload too short: %d bytes" % len(payload))
    reader = BitReader(payload[2:22])        # the 160-bit MCCI block
    reader.take(1)                           # TMCC synchronization word (polarity flips per frame)
    reader.take(1)                           # AC data effective position
    reader.take(2)                           # reserved
    reader.take(4)                           # initialization timing indicator
    iip = {"pointer": (payload[0] << 8) | payload[1]}
    iip["mode"] = MODES.get(reader.take(2), UNKNOWN)
    iip["guard"] = GUARDS.get(reader.take(2), UNKNOWN)
    iip["next_mode"] = MODES.get(reader.take(2), UNKNOWN)
    iip["next_guard"] = GUARDS.get(reader.take(2), UNKNOWN)
    reader.take(2)                           # system identifier
    reader.take(4)                           # count down index
    iip["ewbs"] = bool(reader.take(1))       # the next frame's copy of B26
    iip["partial"] = bool(reader.take(1))
    iip["layer_a"] = _layer(reader)
    iip["layer_b"] = _layer(reader)
    iip["layer_c"] = _layer(reader)
    reader.take(1)                           # next partial reception
    for _ in range(3):
        _layer(reader)                       # next A/B/C
    reader.take(3)                           # phase correction
    reader.take(12)                          # TMCC reserved
    reader.take(10)                          # reserved for future use
    reader.take(32)                          # CRC_32
    iip["branch"] = payload[22]
    iip["last_branch"] = payload[23]
    iip["net_sync_length"] = payload[24]
    iip["crc_ok"] = crc_ok(payload)
    return iip


def _layer_text(name, layer):
    if not layer["present"]:
        return "  %s:-" % name
    return "  %s:%s %s %s %dseg" % (name, layer["modulation"], layer["code_rate"],
                                    layer["interleave"], layer["segments"])


def describe(iip):
    """One line, in the shape the other lines print."""
    line = "iip mode %s  guard %s  partial %d  ewbs %d" % (
        iip["mode"], iip["guard"], int(iip["partial"]), int(iip["ewbs"]))
    line += _layer_text("L0", iip["layer_a"]) + _layer_text("L1", iip["layer_b"])
    line += _layer_text("L2", iip["layer_c"])
    return line


def packets(path, stride=204, pid=IIP_PID, limit=None):
    """Yield the IIP payloads of a recording, whichever stride it uses (188 or 204)."""
    seen = 0
    with open(path, "rb") as handle:
        while True:
            block = handle.read(stride * 20000)
            if not block:
                return
            for index in range(0, len(block) - stride + 1, stride):
                packet = block[index:index + stride]
                if packet[0] != 0x47:
                    continue
                if (((packet[1] & 0x1F) << 8) | packet[2]) != pid:
                    continue
                payload = packet[4:188]
                if packet[3] & 0x20:
                    payload = payload[1 + payload[0]:]
                seen += 1
                yield payload
                if limit is not None and seen >= limit:
                    return
