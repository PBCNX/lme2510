"""Warm a cold stick: the P5b bring-up, from the two images, in one call.

The owner's decision (2026-11-18): the driver does this itself when it needs the stick,
the way the IT drivers do. A cold `3344:1120` is no longer a per-run decision — `play`,
`tune` and `scan` warm it and carry on, printing one line:

    cold 1120 -> warm 2480 (P5b, 75/75)

The frames are **built from the two images** (`ColdDownload`'s rule, the same bytes the
Java side sends): the 500-byte descriptor blob as ten `0x01` frames plus an `0x81` tail of
twelve zeros (the builder sends 512), the 3,138-byte code as sixty-two `0x02` frames plus
an `0x82` tail of 38, then the two-byte MCU reset `8a 00`. Frame layout:
`[cmd][len = payload-1][payload][sum8 = sum(payload) & 0xFF]`.

The images are vendor firmware and never leave the private tree: a public build has no
`firmware/` and the bring-up refuses with one plain line instead of guessing.

Guards kept from the hardware ladder: the bring-up only runs for a device whose product id is
`0x1120` (a warm `2480` must never receive cold frames), the EEPROM path stays untouched,
and the 2 s wait before the reset matches `lmedm04`.
"""
import hashlib
import os
import time

CODE_NAME = "lme2510-code-3138.bin"
CODE_SIZE = 3138
CODE_SHA256 = "0ea1ea14eb6a6e9b9202c2cea32a72747e9b81e042911593c542cbe4d332e656"

BLOB_NAME = "lme2510-usb-descriptors-500.bin"
BLOB_SIZE = 500
BLOB_SHA256 = "b26200b66cead23deb5a29b4e0b704001b85a39f3ddbec4c9b163e68cd4b8c94"

CHUNK = 50
COLD_PID = 0x1120
WARM_PID = 0x2480
ACK = 0x88


class BringUpError(RuntimeError):
    """The stick did not warm. The message is a line a caller can show as is."""


def images_dir(root=None):
    root = root or os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                                "firmware")
    return root


def _read(root, name, size, sha256):
    path = os.path.join(images_dir(root), name)
    if not os.path.isfile(path):
        raise BringUpError("cold 1120: no firmware on this host (%s is missing)" % name)
    with open(path, "rb") as handle:
        data = handle.read()
    if len(data) != size:
        raise BringUpError("cold 1120: %s is %d bytes, expected %d"
                           % (name, len(data), size))
    actual = hashlib.sha256(data).hexdigest()
    if actual != sha256:
        raise BringUpError("cold 1120: %s has SHA-256 %s, expected %s"
                           % (name, actual, sha256))
    return data


def load_images(root=None):
    """The two images, verified by size and SHA-256, or a one-line refusal."""
    code = _read(root, CODE_NAME, CODE_SIZE, CODE_SHA256)
    blob = _read(root, BLOB_NAME, BLOB_SIZE, BLOB_SHA256)
    return code, blob


def frame(command, payload, start=0, length=CHUNK):
    """One `[cmd][len-1][payload][sum8]` frame."""
    body = payload[start:start + length]
    if not body:
        raise ValueError("empty frame")
    return bytes([command, len(body) - 1]) + body + bytes([sum(body) & 0xFF])


def build(code, blob):
    """The 75 frames, in order: descriptor phase, code phase, MCU reset."""
    frames = []
    padded = blob + bytes(512 - len(blob))          # the builder sends 512 bytes
    for offset in range(0, 500, CHUNK):
        frames.append(frame(0x01, padded, offset, CHUNK))
    frames.append(frame(0x81, padded, 500, 12))
    for offset in range(0, len(code) - (len(code) % CHUNK), CHUNK):
        frames.append(frame(0x02, code, offset, CHUNK))
    tail = len(code) % CHUNK
    frames.append(frame(0x82, code, len(code) - tail, tail))
    frames.append(bytes([0x8A, 0x00]))
    if len(frames) != 75:
        raise BringUpError("cold 1120: built %d frames, expected 75" % len(frames))
    return frames


def bring_up(device, frames, wait=time.sleep, settle=2.0, look_for_warm=None,
             timeout=8.0, poll=0.25):
    """Send the frames to an open cold device; return the warm device or raise.

    ``device`` needs `write(frame)` and `read(count)`. ``look_for_warm`` returns the warm
    device once it enumerates (the transport's own finder, normally).
    """
    for index, data in enumerate(frames, start=1):
        device.write(data)
        if index == len(frames):
            wait(settle)                 # lmedm04 waits 2 s before the reset
            continue
        reply = device.read(1)
        if not reply or reply[0] != ACK:
            raise BringUpError("cold 1120: bring-up failed (frame %d no ACK)" % index)
    deadline = time.monotonic() + timeout
    while True:
        warm = look_for_warm() if look_for_warm else None
        if warm is not None:
            return warm
        if time.monotonic() >= deadline:
            break
        wait(poll)
    raise BringUpError("cold 1120: no 2480 after %.0f s" % timeout)
