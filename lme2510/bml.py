# Copyright (c) 2026 The LME2510 Open Driver Authors.  MIT.
# Marker: lme2510-pbcnx.
"""BML documents from the ISDB-T data broadcast (ARIB STD-B24).

The data service rides the type-13 (`0x0D`, DSM-CC) streams. Our 599143 capture
carries a full document on PID `0x0511`:

    <bml><head><title>top.bml</title><style>...<script>...

This module finds documents in a capture or in a live buffer and extracts them.
It is a *document extractor*, not a BML engine: it does not render, run the
script, or resolve the carousel's module tree. What it gives a host is the
document — title and text — and the PID it came from, which is enough to hand to a
real BML browser (`a TV front end`, or the studio's own).

The stream interleaves DSM-CC headers between the documents, so a document ends up
with control bytes in it; the extractor keeps the bytes and the text shows the
markup, which is what a browser would parse after the carousel layer.
"""
BML_START = b"<bml"
BML_END = b"</bml>"
TITLE_START = b"<title>"
TITLE_END = b"</title>"


class Document:
    def __init__(self, pid, raw):
        self.pid = pid
        self.raw = raw
        self.text = raw.decode("utf-8", "replace")
        self.title = self._title()
        self.count = 1

    def _title(self):
        start = self.raw.find(TITLE_START)
        if start < 0:
            return ""
        start += len(TITLE_START)
        end = self.raw.find(TITLE_END, start)
        if end < 0:
            return ""
        return self.raw[start:end].decode("utf-8", "replace").strip()

    @property
    def size(self):
        return len(self.raw)

    def __repr__(self):
        return "<BML %r %d bytes on 0x%04x>" % (self.title, self.size, self.pid)


def payloads(data):
    """``{pid: [payload, ...]}`` for every packet in the capture."""
    out = {}
    for start in range(0, len(data) - 187, 188):
        packet = data[start:start + 188]
        if packet[0] != 0x47:
            continue
        pid = ((packet[1] & 0x1F) << 8) | packet[2]
        afc = (packet[3] >> 4) & 3
        begin = 4 + (1 + packet[4] if afc in (2, 3) else 0)
        out.setdefault(pid, []).append(packet[begin:])
    return out


def documents(data, pids=None, limit=32, unique=True):
    """Every BML document in the buffer, as :class:`Document` objects.

    ``pids`` limits the scan to the streams a host already knows are the data
    service; by default every PID is tried, which is what a capture without a PMT
    needs.  ``unique`` collapses the carousel: a data service repeats its documents,
    so the same bytes arrive time after time — the returned document carries the
    number of times it was seen in ``count``.
    """
    found = []
    seen = {}
    for pid, chunks in payloads(data).items():
        if pids is not None and pid not in pids:
            continue
        blob = b"".join(chunks)
        at = 0
        while len(found) < limit:
            start = blob.find(BML_START, at)
            if start < 0:
                break
            end = blob.find(BML_END, start)
            if end < 0:
                break
            raw = blob[start:end + len(BML_END)]
            at = end + len(BML_END)
            if unique:
                key = (pid, len(raw), hash(raw))
                if key in seen:
                    seen[key].count += 1
                    continue
                document = Document(pid, raw)
                seen[key] = document
                found.append(document)
            else:
                found.append(Document(pid, raw))
    return found


