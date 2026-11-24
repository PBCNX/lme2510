"""Reading the TS endpoint: several bulk reads in flight, written in order.

The vendor keeps four URBs of 51,200 B in flight, so a pool of reader threads does
the same.  Within the pool, each chunk is numbered **before** its read and a single
writer emits them in sequence order — without that, chunks land in completion order
and packets cross boundaries (measured: 339 CC jumps in one 8 s run, against 2 with
the ordered writer).

    reader = StreamReader(dev, sink=handle.write)
    total = reader.run(seconds=10)
"""
import itertools
import threading
import time

from .transport import PacketAligner

CHUNK = 51200
ENDPOINT = 0x88


def pyusb_source(device, endpoint=ENDPOINT):
    """A `source(size, timeout_ms)` over a raw pyusb device."""

    def source(size, timeout_ms):
        return bytes(device.read(endpoint, size, timeout=timeout_ms))
    return source


def transport_source(device, endpoint=None):
    """A `source(size, timeout_ms)` over an `lme2510.transport.Device`.

    When `endpoint` is None the device is asked which one carries the stream
    (`Device.ts_endpoint`): it is not always 0x88.
    """
    chosen = endpoint if endpoint is not None else device.ts_endpoint()

    def source(size, timeout_ms):
        return bytes(device.read(size, endpoint=chosen, timeout=timeout_ms))
    return source


class DeviceGone(RuntimeError):
    """The device left the bus under us (seen on the LEAP 2026-11-18).

    A stick that resets re-enumerates — sometimes as the cold `3344:1120`, which the host
    then has to warm again. Until this existed the reader swallowed the error, the reads
    returned empty, and the only symptom was a stall three seconds later; the caller could
    not tell "the mux died" from "the device vanished", and those need different answers.
    """


#: A bulk read that times out is normal on an idle pipe; these are the strings pyusb and
#: libusb use for it, and only a *different* failure counts as the device leaving.
TIMEOUT_HINTS = ("timed out", "timeout", "operation timed out")


def _is_timeout(error):
    text = str(error).lower()
    return any(hint in text for hint in TIMEOUT_HINTS)


class StreamReader:
    def __init__(self, source, readers=4, chunk=CHUNK, timeout_ms=500, sink=None,
                 presence=None, presence_every=2.0):
        self.source = source
        self.chunk = chunk
        self.readers = readers
        self.timeout_ms = timeout_ms
        self.sink = sink or (lambda data: None)
        self.total = 0
        self.chunks = 0
        # `presence()` answers "is the device still enumerated?". Measured on macOS 2026-11-20: a pulled
        # device makes reads *time out* rather than fail, and a timeout is what a quiet pipe looks like —
        # so `play` kept reading a vanished handle for ~40 s before an error surfaced. Silence alone is not
        # a detach; silence plus absence is, and that is what the caller's presence callback supplies.
        self.presence = presence
        self.presence_every = presence_every
        # The packet phase belongs HERE, not in the source: four readers complete out of order, so an aligner
        # called per read sees fragments in completion order and loses packets at every seam (measured
        # 2026-11-23: a 30 s capture carried two misalignment seams and 14 continuity errors because of it).
        # The writer sees the chunks in sequence order, which is the only order a byte stream has.
        self.aligner = PacketAligner()

    def run(self, seconds=None, until=None):
        """Read for ``seconds`` — or until ``until`` is set when seconds is None.

        Live mode (`lme2510 play` with no --seconds, as the a TV front end runs it)
        passes seconds=None and a threading.Event it sets on SIGINT/SIGTERM.
        The internal stop event is separate: this method owns the pool.
        Returns the byte count.
        """
        stop = threading.Event()
        pending = {}
        state = {"next": 0}
        condition = threading.Condition()
        sequence = itertools.count()

        def reader():
            while not stop.is_set():
                seq = next(sequence)
                try:
                    data = self.source(self.chunk, self.timeout_ms)
                except Exception as error:  # noqa: BLE001
                    if not _is_timeout(error):
                        # the endpoint is gone: record it, stop the pool, and let `run`
                        # raise DeviceGone instead of pretending the pipe went quiet
                        state.setdefault("error", error)
                        stop.set()
                    data = b""
                with condition:
                    pending[seq] = data
                    condition.notify_all()

        def writer():
            while True:
                with condition:
                    if not pending and stop.is_set():
                        return
                    while state["next"] not in pending:
                        if stop.is_set() and not pending:
                            return
                        condition.wait(0.5)   # a read may take the timeout: wait
                    data = pending.pop(state["next"], b"")
                    state["next"] += 1
                if data:
                    data = self.aligner.push(data)
                    if data:
                        self.sink(data)
                        self.total += len(data)
                        self.chunks += 1

        drain = threading.Thread(target=writer, daemon=True)
        drain.start()
        pool = [threading.Thread(target=reader, daemon=True)
                for _ in range(self.readers)]
        for thread in pool:
            thread.start()
        deadline = None if seconds is None else time.monotonic() + seconds
        last_total = self.total
        last_change = time.monotonic()
        while True:
            if seconds is None:
                if until is not None and until.is_set():
                    break
            elif time.monotonic() >= deadline:
                break
            time.sleep(0.05)
            if self.total != last_total:
                last_total, last_change = self.total, time.monotonic()
                continue
            if self.presence is None or time.monotonic() - last_change < self.presence_every:
                continue
            try:
                present = self.presence()
            except Exception:  # noqa: BLE001
                present = True              # cannot tell: do not accuse the device
            if not present:
                state.setdefault("error", ConnectionError(
                    "no data for %.0f s and the device is not on the bus"
                    % (time.monotonic() - last_change)))
                break
            last_change = time.monotonic()  # asked and answered: do not re-ask every tick
        stop.set()
        for thread in pool:
            thread.join(timeout=2.0)
        drain.join(timeout=3.0)
        if state.get("error") is not None:
            raise DeviceGone("device left the bus: %s" % state["error"])
        return self.total



