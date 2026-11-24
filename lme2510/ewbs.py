# Copyright (c) 2026 The LME2510 Open Driver Authors.  MIT.
# Marker: lme2510-pbcnx.
"""The EWBS area descriptor (tag `0xFC`), as the PH standard defines it.

`BML-EWS-09` in `_Library/Standards/ISDBTph-Standards/bml-standards-guide.md`
(ABNT-1 Table 9, ABNT-2 §8.2) sets the EWBS area descriptor's tag to **0xFC** and
gives its fields; `EWBS-PH-Mapping/ gives the wire form of an
area: a 16-bit big-endian word `area_code << 4 | 0xF`, 12 bits used.

Layout as measured on air (2026-11-15, 599143 kHz):

    service_id   2 bytes
    flags        1 byte    start_end_flag in bit 7, signal_level in bits 6-4
    length       1 byte    bytes of area codes that follow (36 -> 18 codes)
    area codes   length bytes, each 2: (area_code << 4 | 0xF) big-endian

The 12-bit code is `zone(3) | region(4) | city(5)`: 0x200 nationwide, 0x400 Luzon,
0x600 Visayas, 0x800 Mindanao, then 17 regions and 118 province/city entries.

A receiver acts on this: `start_end_flag 1` starts an alert, `0` ends it, and the
area codes say where. The monthly test signal (`BML-EWS-11`) is what broadcasts
one outside a real emergency.
"""
EWBS_TAG = 0xFC

ZONE_NATIONWIDE = 0x200
ZONE_LUZON = 0x400
ZONE_VISAYAS = 0x600
ZONE_MINDANAO = 0x800

ZONE_NAMES = {
    ZONE_NATIONWIDE: "nationwide",
    ZONE_LUZON: "Luzon",
    ZONE_VISAYAS: "Visayas",
    ZONE_MINDANAO: "Mindanao",
    0xA00: "Mindanao",          # 0xA00 also appears in the allocation
}

START_END_BIT = 0x80
SIGNAL_SHIFT = 4
SIGNAL_MASK = 0x7
RESERVED_NIBBLE = 0xF


def zone_name(code):
    """The island group a 12-bit area code belongs to, or None."""
    for base, name in sorted(ZONE_NAMES.items(), reverse=True):
        if code >= base:
            return name
    return None


def split(code):
    """``(zone, region, city)`` from a 12-bit area code."""
    return (code >> 9, (code >> 5) & 0xF, code & 0x1F)


def parse(payload):
    """Decode an EWBS descriptor body (the bytes after the tag and length).

    Returns a dict, or raises ValueError when the payload cannot be one.
    """
    if len(payload) < 4:
        raise ValueError("EWBS descriptor too short: %d byte(s)" % len(payload))
    service = (payload[0] << 8) | payload[1]
    flags = payload[2]
    length = payload[3]
    if len(payload) < 4 + length:
        raise ValueError("EWBS descriptor says %d area-code bytes, %d present"
                         % (length, len(payload) - 4))
    codes = []
    reserved_ok = True
    for index in range(length // 2):
        word = (payload[4 + 2 * index] << 8) | payload[5 + 2 * index]
        codes.append(word >> 4)
        if (word & RESERVED_NIBBLE) != RESERVED_NIBBLE:
            reserved_ok = False
    return {
        "service_id": service,
        "start_end_flag": (flags & START_END_BIT) >> 7,
        "signal_level": (flags >> SIGNAL_SHIFT) & SIGNAL_MASK,
        "signal_byte": flags,
        "area_codes": codes,
        "reserved_nibble_ok": reserved_ok,
        "length": length,
    }


def descriptors(section):
    """Every EWBS descriptor in a PMT section, as parsed dicts."""
    if len(section) < 12 or section[0] != 0x02:
        return []
    found = []
    info_length = ((section[10] & 0x0F) << 8) | section[11]
    loops = [section[12:12 + info_length]]
    index = 12 + info_length
    while index + 5 <= len(section) - 4:
        es_length = ((section[index + 3] & 0x0F) << 8) | section[index + 4]
        loops.append(section[index + 5:index + 5 + es_length])
        index += 5 + es_length
    for blob in loops:
        at = 0
        while at + 2 <= len(blob):
            tag, length = blob[at], blob[at + 1]
            if tag == EWBS_TAG:
                try:
                    found.append(parse(blob[at + 2:at + 2 + length]))
                except ValueError:
                    pass
            at += 2 + length
    return found
