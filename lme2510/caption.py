# Copyright (c) 2026 The LME2510 Open Driver Authors.  MIT.
# Marker: lme2510-pbcnx.
"""ARIB caption text out of the private PES streams (`private_stream_2`, 0xBF).

ISDB-T captions travel in PES `00 00 01 BF` with no optional header: the body
starts at byte 6.  The body is an ARIB STD-B24 statement — a short header, the
display parameters (`S`, `V`, `X`, `Y`, `W`, `h`), then the text, every token
preceded by 8-bit control codes, and a CRC-16 at the end.

The text is single-byte, so the decode is exact: keep the printable bytes and use
each control byte as a **separator** (dropping them instead glues `30;89 a` to the
caption).  The caption itself is the longest printable field, since it is padded
with spaces and therefore outruns the parameters.

Measured on 599143 kHz, 2026-11-15:

    parameters: 7 S 620;480 V 30;30 _ 4 X 24 Y 36;36 W 0 h 30;89 a
    text:       'a TV front end                                  ALERT 69420'
"""
import re

PRIVATE_STREAM_2 = 0xBF
SEPARATOR = "\x00"

_GLUED_PARAMETER = re.compile(r"^(\d+;\d+\s+a)(.+)$")


def pes_bodies(data):
    """Yield ``(pid, body)`` for every private_stream_2 PES in a capture."""
    for start in range(0, len(data) - 187, 188):
        packet = data[start:start + 188]
        if packet[0] != 0x47 or not (packet[1] & 0x40):
            continue
        pid = ((packet[1] & 0x1F) << 8) | packet[2]
        afc = (packet[3] >> 4) & 3
        if afc not in (1, 3):
            continue
        begin = 4 + (1 + packet[4] if afc in (2, 3) else 0)
        payload = packet[begin:]
        if len(payload) < 6 or payload[:3] != b"\x00\x00\x01":
            continue
        if payload[3] != PRIVATE_STREAM_2:
            continue
        length = (payload[4] << 8) | payload[5]
        yield pid, payload[6:6 + length]


def decode_text(body):
    """Printable bytes kept, every control byte turned into a separator."""
    return "".join(chr(byte) if 0x20 <= byte < 0x7F else SEPARATOR for byte in body)


def statement(body):
    """Split an ARIB statement into ``(parameters, text)``."""
    pieces = [piece.strip() for piece in decode_text(body).split(SEPARATOR)]
    pieces = [piece for piece in pieces if piece]
    candidates = [piece for piece in pieces if len(piece) >= 4]   # skip header scraps
    if not candidates:
        return "", ""
    text = max(candidates, key=len)
    parameters = " ".join(pieces[:pieces.index(text)])
    match = _GLUED_PARAMETER.match(text)
    if match:
        parameters = (parameters + " " + match.group(1).strip()).strip()
        text = match.group(2)
    if text.startswith("a") and len(text) > 1 and text[1].isupper():
        text = text[1:]
    return parameters, text


