# Copyright (c) 2026 The LME2510 Open Driver Authors.  MIT.
# Marker: lme2510-pbcnx.
"""PSI sections out of a TS capture: assembly, descriptor loops, PAT/PMT.

Every analyser in this tree needs the same three steps — reassemble a PID's
sections, walk a descriptor loop, find the PMTs — so they live here instead of in
each tool.
"""
from lme2510 import sections as psi


def iter_sections(data, pid):
    """Yield complete PSI sections for one PID, in order."""
    buffer = b""
    for start in range(0, len(data) - 187, 188):
        packet = data[start:start + 188]
        if packet[0] != 0x47:
            continue
        if (((packet[1] & 0x1F) << 8) | packet[2]) != pid:
            continue
        pusi = bool(packet[1] & 0x40)
        afc = (packet[3] >> 4) & 3
        begin = 4 + (1 + packet[4] if afc in (2, 3) else 0)
        body = packet[begin:]
        if pusi:
            if body:
                body = body[1 + body[0]:]        # pointer_field
            buffer = body
        elif buffer:
            buffer += body
        while len(buffer) >= 3:
            length = 3 + (((buffer[1] & 0x0F) << 8) | buffer[2])
            if len(buffer) < length:
                break
            yield buffer[:length]
            buffer = buffer[length:]


def walk(blob):
    """Yield (tag, payload) for a well-formed descriptor loop."""
    index = 0
    while index + 2 <= len(blob):
        tag, length = blob[index], blob[index + 1]
        yield tag, blob[index + 2:index + 2 + length]
        index += 2 + length


def loops_of(section):
    """The descriptor loops of a PMT, as ``(label, blob)`` pairs."""
    loops = []
    if len(section) < 12 or section[0] != 0x02:
        return loops
    info_length = ((section[10] & 0x0F) << 8) | section[11]
    loops.append(("program", section[12:12 + info_length]))
    index = 12 + info_length
    while index + 5 <= len(section) - 4:
        es_type = section[index]
        es_pid = ((section[index + 1] & 0x1F) << 8) | section[index + 2]
        es_length = ((section[index + 3] & 0x0F) << 8) | section[index + 4]
        loops.append(("es 0x%02x pid 0x%04x" % (es_type, es_pid),
                      section[index + 5:index + 5 + es_length]))
        index += 5 + es_length
    return loops


def programs(data):
    """``[(program_number, pmt_pid), ...]`` from the capture's PAT."""
    pat = next(iter_sections(data, 0x0000), b"")
    return psi.parse_pat(pat) if pat else []

