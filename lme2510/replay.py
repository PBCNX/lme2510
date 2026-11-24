"""Replay engine — a captured script with named fields.

The IT9308 method: the driver replays what the vendor sent and
changes only the fields that vary.  Here those fields are

- the tune word: one write each to ``0f51``, ``0f57``, ``0f5a``, ``0f58``, ``0f59``
  (computed by :mod:`lme2510.fc8300`), and
- the TS filter's PID list.

Nothing in this module opens a device.  It turns a captured frame list into the
frames for another channel and reports exactly what it changed, so a replay can be
diffed against a capture before a single byte goes out.
"""

from . import fc8300

# 04 <len=4> <device 0xb0> <register hi> <register lo> <value>
WRITE_PREFIX = bytes((0x04, 0x04, 0xB0))

# The tuner's lock-status register, as the captured set-frequency burst polls it
# W4: `04 03 b0 30 25` + `86 02 b1 01 25`, eight times).
LOCK_REGISTER = 0x3025

# The status register the emulated `fc8300b_set_freq` polls after the PLL write.
STATUS_REGISTER = 0x0F76

# The two devices a warm frame addresses: the tuner answers on 0xb0 and the
# demodulator on 0xbe; a read addresses device|1 (0xb1 / 0xbf).
TUNER_DEVICE = 0xB0
DEMOD_DEVICE = 0xBE

# The tune field, in the order the tune writes it.
TUNE_REGISTERS = (0x0F51, 0x0F57, 0x0F5A, 0x0F58, 0x0F59)

# The filter frame's tail byte base: pass-through vs filtered §3.5).
FILTER_TAIL_PASSTHROUGH = 0x7F
FILTER_TAIL_FILTERED = 0x7E


def write_frame(register, value, device=None):
    """The 6-byte frame that writes one byte: ``04 04 <device> <hi> <lo> <value>``.

    The device defaults to the tuner (0xb0); the demod is 0xbe.  The tune writes
    both (the band plan and the tail go to the demod, the PLL word to the tuner),
    so the caller can pick.
    """
    device = TUNER_DEVICE if device is None else device
    return bytes((0x04, 0x04, device, (register >> 8) & 0xFF, register & 0xFF,
                  value & 0xFF))


def read_register(frame):
    """Return the register a 6-byte write frame targets, or None."""
    if len(frame) == 6 and frame[:3] == WRITE_PREFIX:
        return (frame[3] << 8) | frame[4]
    return None


def select_frame(register, device=TUNER_DEVICE):
    """The select a read is preceded by: ``04 03 <device> <hi> <lo>``.

    Five bytes, no value: the device only latches the register address.  The
    captured lock poll on 2026-11-14 is ``04 03 b0 30 25``.
    """
    return bytes((0x04, 0x03, device, (register >> 8) & 0xFF, register & 0xFF))


def read_frame(register, count=1, device=TUNER_DEVICE):
    """The read that follows a select: ``86 02 <device|1> <count> <lo>``.

    The fifth byte is the residue of the static buffer at ``0x45bba``: the SDK
    builds four bytes and sends five, and the extra byte is the register's low
    byte in every capture (``3025`` → ``25``).  Reproducing it matches the
    vendor; `` says four bytes is the honest form and is
    untested, so we send five.  The answer is ``55`` + ``count`` payload bytes.
    """
    if not 1 <= count <= 0xFF:
        raise ValueError("a read asks for 1..255 bytes, not %d" % count)
    return bytes((0x86, 0x02, device | 0x01, count, register & 0xFF))


def status_register(register):
    """The register of a ``04 03 <dev> <hi> <lo>`` select, or None."""
    if len(register) == 5 and register[0] == 0x04 and register[1] == 0x03:
        return (register[3] << 8) | register[4]
    return None


def stream_start_frames(pids, filtered=False):
    """The two frames that start the stream: the filter, then ``06 00``."""
    return [ts_filter_frame(pids, filtered=filtered), bytes((0x06, 0x00))]


def ts_filter_frame(pids, filtered=False):
    """The filter/settings frame the vendor sends before it starts the stream.

    ``03 | 2+4n | per PID {reg 2i <- pid_lo, reg 2i+1 <- pid_hi} | 20 | tail``,
    with ``tail = 0x7f+2n`` for pass-through and ``0x7e+2n`` when filtered.  For
    mode 0 with PID ``0x1fff`` this is the eight bytes captured from the phone:
    ``03 06 00 ff 01 1f 20 81``.
    """
    if not pids:
        raise ValueError("a filter frame needs at least one PID")
    if len(pids) > 30:
        raise ValueError("the frame's length byte cannot carry %d PIDs" % len(pids))
    body = bytearray()
    for index, pid in enumerate(pids):
        body += bytes((2 * index, pid & 0xFF, 2 * index + 1, (pid >> 8) & 0xFF))
    length = 2 + len(body)
    tail = (FILTER_TAIL_FILTERED if filtered else FILTER_TAIL_PASSTHROUGH) + 2 * len(pids)
    return bytes((0x03, length)) + bytes(body) + bytes((0x20, tail & 0xFF))


def tune_patches(frames, freq_khz):
    """The tune field's writes for ``freq_khz``, as ``[(index, register, old, new)]``.

    Only frames that already carry the field are patched: a replay keeps the
    captured structure and changes the values, exactly like IT9308 patching its
    ``09 24 67`` field.
    """
    wanted = dict(fc8300.synth_word(freq_khz)["writes"])
    patches = []
    for index, frame in enumerate(frames):
        register = read_register(frame)
        if register in wanted and frame[5] != wanted[register]:
            patches.append((index, register, frame[5], wanted[register]))
    return patches


def apply_tune(frames, freq_khz):
    """Return ``(new_frames, patches)`` for a replay at ``freq_khz``."""
    patches = tune_patches(frames, freq_khz)
    out = list(frames)
    for index, register, _old, new in patches:
        out[index] = write_frame(register, new)
    return out, patches


def summary(frames):
    """Counts for a replay report: frames, bytes, and per-command totals."""
    commands = {}
    for frame in frames:
        if not frame:
            continue
        commands[frame[0]] = commands.get(frame[0], 0) + 1
    return {
        "frames": len(frames),
        "bytes": sum(len(frame) for frame in frames),
        "commands": commands,
    }


def load_manifest(path):
    """Read a ``run.py --dump`` manifest into a list of OUT payloads."""
    frames = []
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            parts = line.split()
            if len(parts) >= 5 and parts[0] == "out":
                frames.append(bytes.fromhex(parts[4]))
    return frames
