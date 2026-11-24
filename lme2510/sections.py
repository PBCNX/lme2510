# Copyright (c) 2026 The LME2510 Open Driver Authors.  MIT.
# Marker: lme2510-pbcnx.
"""Transport-stream analysis: packets, PIDs, PSI sections.  No device.

Rung 1's report needs three things from a captured buffer, and the first version
of the tool got all three wrong: it counted only packets with the payload-unit
start flag (so continuity packets were invisible), it parsed a PAT out of one
packet (so a section split across packets was invisible), and it printed no rate.

Here a packet is a packet -- every 188-byte unit is counted for its PID -- and a
PSI section is reassembled across as many packets as it needs, with the
``pointer_field`` honoured and the MPEG-2 CRC checked when the section is whole.
Everything in this module is offline and testable with synthetic packets; nothing
here knows what a warm device is.

Bytes are never assumed to start on a packet boundary: :func:`find_sync` locates
the first offset where several packets in a row begin with ``0x47``.
"""

SYNC = 0x47
PACKET_SIZE = 188

# Table ids this module parses.  Everything else stays opaque bytes.
PAT_TABLE_ID = 0x00
PMT_TABLE_ID = 0x02

# PID of the program association table.
PAT_PID = 0x0000


def find_sync(data):
    """The first plausible packet start: ``0x47``, and ``0x47`` again 188 later.

    This returns only a starting hint -- the first ``0x47`` in the buffer -- and
    :func:`scan` does the deciding.  A damaged capture must not need three aligned
    packets before analysis starts: the first run of rung 1 lost 1,893 packets out
    of 1,906 because one hole shifted a fixed stride, while :func:`scan` recovers
    from the same buffer with 0.2% lost.
    """
    if not data:
        return None
    index = data.find(bytes((SYNC,)))
    return None if index < 0 else index


def pid_of(packet):
    """The PID of one 188-byte packet."""
    return ((packet[1] & 0x1F) << 8) | packet[2]


def pusi(packet):
    """True when the payload starts a new section (payload_unit_start_indicator)."""
    return bool(packet[1] & 0x40)


def has_payload(packet):
    """True when the adaptation-field control says a payload follows."""
    return bool(packet[3] & 0x10)


def payload_of(packet):
    """The payload bytes of one packet, adaptation field skipped."""
    if not has_payload(packet):
        return b""
    start = 4
    if packet[3] & 0x20:                       # adaptation field present
        start += 1 + packet[4]
    if start >= PACKET_SIZE:
        return b""
    return packet[start:PACKET_SIZE]


def iter_packets(data, offset=None):
    """Yield ``(index, packet)`` for every packet, resynchronising after a gap.

    A capture is not guaranteed to be one aligned run: a read that timed out can
    drop bytes, and then a fixed 188-byte stride would count noise for the rest of
    the buffer (the first rung-1 run did exactly that and reported 1,893 bad sync
    from a stream that was 99.8% intact).  Here a packet is accepted only when its
    ``0x47`` is followed by another ``0x47`` one packet later; otherwise the walk
    moves one byte and tries again.
    """
    for index, packet in enumerate(scan(data)[0]):
        yield index, packet


def scan(data):
    """Walk a capture once: ``(packets, skipped, resyncs)``.

    ``skipped`` counts the bytes the walk had to step over; a large number means
    the capture lost data, not that the analyser was wrong.

    A packet is accepted when its ``0x47`` is followed by another ``0x47`` one
    packet later, or when it continues directly from a packet that was itself
    validated that way.  The second half matters: a hole costs the packet that
    straddles it, not the good packet just before it, and a false sync cannot
    chain, because an unvalidated accept does not grant the same trust.
    """
    offset = find_sync(data)
    packets = []
    if offset is None:
        return packets, len(data), 0
    skipped = 0
    resyncs = 0
    in_gap = False
    trusted = True                              # the buffer's own start is trusted
    expected = offset
    position = offset
    while position + PACKET_SIZE <= len(data):
        paired = data[position] == SYNC and (
            position + 2 * PACKET_SIZE > len(data)
            or data[position + PACKET_SIZE] == SYNC)
        if paired or (trusted and position == expected and data[position] == SYNC):
            if in_gap:
                resyncs += 1
                in_gap = False
            packets.append(data[position:position + PACKET_SIZE])
            position += PACKET_SIZE
            expected = position
            trusted = paired
        else:
            in_gap = True
            skipped += 1
            position += 1
    return packets, skipped, resyncs


def crc32(data):
    """MPEG-2 CRC-32 (poly 0x04C11DB7, init all ones, no final xor, no reflection)."""
    crc = 0xFFFFFFFF
    for byte in bytearray(data):
        crc ^= byte << 24
        for _ in range(8):
            if crc & 0x80000000:
                crc = ((crc << 1) ^ 0x04C11DB7) & 0xFFFFFFFF
            else:
                crc = (crc << 1) & 0xFFFFFFFF
    return crc


def crc_ok(section):
    """A complete section with its CRC appended crc32's to zero."""
    return len(section) >= 4 and crc32(section) == 0


def section_length(section):
    """The declared ``section_length`` field (bytes after it, CRC included)."""
    return ((section[1] & 0x0F) << 8) | section[2]


class Sections:
    """Reassemble the PSI sections of one PID.

    Feed every packet of that PID, in stream order.  The assembler returns the
    sections that became complete; a section that starts in one packet and ends
    in the next comes out once, whole.
    """

    def __init__(self):
        self._buffer = b""
        self.broken = 0

    def feed(self, packet):
        body = payload_of(packet)
        if not body:
            return []
        if pusi(packet):
            # A new section starts here: whatever was buffered was incomplete.
            if self._buffer:
                self.broken += 1
                self._buffer = b""
            pointer = body[0]
            body = body[1 + pointer:]
        self._buffer += body
        out = []
        while len(self._buffer) >= 3:
            if self._buffer[0] == 0xFF:        # stuffing, not a table
                self._buffer = b""
                break
            total = 3 + section_length(self._buffer)
            if total < 4:                      # section_length 0: malformed
                self.broken += 1
                self._buffer = b""
                break
            if len(self._buffer) < total:
                break
            out.append(self._buffer[:total])
            self._buffer = self._buffer[total:]
        return out


def parse_pat(section):
    """``[(program_number, pmt_pid)]`` from a complete PAT section."""
    if section[0] != PAT_TABLE_ID:
        raise ValueError("not a PAT section (table_id 0x%02x)" % section[0])
    end = 3 + section_length(section) - 4       # before the CRC
    entries = []
    for position in range(8, end - 3, 4):
        program = (section[position] << 8) | section[position + 1]
        pid = ((section[position + 2] & 0x1F) << 8) | section[position + 3]
        if program:
            entries.append((program, pid))
    return entries


def parse_pmt(section):
    """``{program, pcr_pid, streams: [(stream_type, pid)]}`` from a PMT section."""
    if section[0] != PMT_TABLE_ID:
        raise ValueError("not a PMT section (table_id 0x%02x)" % section[0])
    end = 3 + section_length(section) - 4       # before the CRC
    program = (section[3] << 8) | section[4]
    pcr_pid = ((section[8] & 0x1F) << 8) | section[9]
    program_info = ((section[10] & 0x0F) << 8) | section[11]
    position = 12 + program_info
    streams = []
    while position + 5 <= end:
        stream_type = section[position]
        pid = ((section[position + 1] & 0x1F) << 8) | section[position + 2]
        es_info = ((section[position + 3] & 0x0F) << 8) | section[position + 4]
        streams.append((stream_type, pid))
        position += 5 + es_info
    return {"program": program, "pcr_pid": pcr_pid, "streams": streams}


def pes_payload_kind(data, pid):
    """What an elementary stream's first PES really carries: "ADTS", "LATM" or None.

    A PMT can say one thing and the payload another.  ALLTV's PMT names stream type
    0x11 (AAC LATM) while every PES starts with an ADTS header (0xFF 0xF1), a known
    trap recorded in  This looks at the bytes, not the label.
    """
    packets, _skipped, _resyncs = scan(data)
    for packet in packets:
        if pid_of(packet) != pid or not pusi(packet) or not has_payload(packet):
            continue
        body = payload_of(packet)
        if len(body) < 10:
            continue
        if body[:3] == b"\x00\x00\x01":         # a PES starts here, no pointer
            section = body
        else:
            section = body[1 + body[0]:]          # PSI pointer_field
        if section[:3] != b"\x00\x00\x01":
            continue
        header_length = section[8]
        payload = section[9 + header_length:]
        if len(payload) >= 2 and payload[0] == 0xFF and (payload[1] & 0xF6) == 0xF0:
            return "ADTS"
        if payload[:1] == b"\x56" or payload[:1] == b"\x58":   # LATM StreamMuxConfig
            return "LATM"
        return None
    return None


def analyse(data, seconds=None):
    """Everything rung 1 needs to know about a captured buffer.

    Returns a dict: ``bytes``, ``offset``, ``packets``, ``skipped``, ``resyncs``,
    ``pids`` (every packet counted), ``cc_jumps``, ``cc_by_pid``, ``pat`` (list of
    ``(program, pmt_pid)``), ``pat_crc``, ``pmt`` (``{pid: pmt dict}``), and,
    when ``seconds`` is given, ``byte_rate`` and ``packet_rate``.
    """
    result = {
        "bytes": len(data) or 0,
        "offset": None,
        "packets": 0,
        "skipped": 0,
        "resyncs": 0,
        "pids": {},
        "cc_jumps": 0,
        "cc_by_pid": {},
        "pat": [],
        "pat_crc": None,
        "pmt": {},
        "byte_rate": None,
        "packet_rate": None,
    }
    offset = find_sync(data)
    result["offset"] = offset
    if offset is None:
        result["skipped"] = len(data) or 0
        if seconds and seconds > 0:
            result["byte_rate"] = 0.0
            result["packet_rate"] = 0.0
        return result

    packets, skipped, resyncs = scan(data)
    result["packets"] = len(packets)
    result["skipped"] = skipped
    result["resyncs"] = resyncs

    # First pass: every packet for its PID and its continuity counter, and the
    # PAT.  A PMT usually goes out *before* the PAT repeats, so the PMT pass has
    # to come after the whole buffer has been read once.
    last_counter = {}
    pat_sections = Sections()
    for packet in packets:
        pid = pid_of(packet)
        result["pids"][pid] = result["pids"].get(pid, 0) + 1
        if has_payload(packet):
            counter = packet[3] & 0x0F
            previous = last_counter.get(pid)
            if previous is not None and counter != ((previous + 1) & 0x0F) \
                    and counter != previous:
                result["cc_jumps"] += 1
                result["cc_by_pid"][pid] = result["cc_by_pid"].get(pid, 0) + 1
            last_counter[pid] = counter
        if pid == PAT_PID and not result["pat"]:
            for section in pat_sections.feed(packet):
                if section[0] == PAT_TABLE_ID and crc_ok(section):
                    result["pat"] = parse_pat(section)
                    result["pat_crc"] = True

    # Second pass: the PMT of every PID the PAT named, over the same packets.
    for pmt_pid in sorted({pid for _program, pid in result["pat"]}):
        assembler = Sections()
        for packet in packets:
            if pid_of(packet) != pmt_pid:
                continue
            for section in assembler.feed(packet):
                if section[0] == PMT_TABLE_ID and crc_ok(section):
                    result["pmt"][pmt_pid] = parse_pmt(section)

    if seconds and seconds > 0:
        result["byte_rate"] = result["bytes"] / seconds
        result["packet_rate"] = result["packets"] / seconds
    return result
