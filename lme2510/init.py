"""The warm init, composed: captured literal blocks around transcribed modules. §3 lays the 330-frame init out range by range.  This runs
it: the bbm bring-up and the four literal blocks come from
[`init_prefix.py`](init_prefix.py) / [`init_blocks.py`](init_blocks.py) — bytes
with no decisions in them — and the parts that *do* decide are our own code:

    85..161  the 77-write register script    lme2510/init_script.py
   176..201  the VCO calibration            lme2510/calibration.py
   235..297  the 0f1c sweep                 lme2510/sweep.py

`run(exchange)` drives a transport: ``exchange(frame)`` sends one frame and
returns the device's reply (the `0x55` payload for a read, `0x88` for a write
ACK).  Reads the modules issue are turned into the vendor's select+read pair here.
`tests/test_init.py` feeds it the replies the capture actually got and checks the
emitted frames are the captured init, byte for byte.
"""
from lme2510 import calibration, init_blocks, init_prefix, init_script, replay, sweep

DEFAULT_FREQ = 545143      # what Tuner Init tunes before the app does
PAGE = 0x0F00


def _read_with(exchange):
    """A `read(register, count)` built on the transport's exchange."""

    def read(register, count=1):
        exchange(replay.select_frame(register))
        reply = exchange(replay.read_frame(register, count))
        if len(reply) >= 1 + count and reply[0] == 0x55:
            return reply[1:1 + count]
        return b""
    return read


def run(exchange, crystal=0x3E, wait=None):
    """Run the whole warm init.  Returns the sweep's ``(peak, levels)``."""
    for frame in init_prefix.FRAMES:
        exchange(frame)
    for frame in init_script.frames(crystal, device=0xBE):
        exchange(frame)
    for frame in init_blocks.AFTER_SCRIPT:
        exchange(frame)

    read = _read_with(exchange)
    locked, _column = calibration.run(exchange, read, selector=0, wait=wait)
    if not locked:
        raise RuntimeError("VCO calibration failed: the init would leave the "
                           "tuner uncalibrated (see calibration.run)")

    for frame in init_blocks.DEFAULT_TUNE:
        exchange(frame)
    for frame in init_blocks.AFTER_DEFAULT_TUNE:
        exchange(frame)

    peak, levels = sweep.run(exchange, read, wait=wait)
    for frame in init_blocks.AFTER_SWEEP:
        exchange(frame)
    return peak, levels

