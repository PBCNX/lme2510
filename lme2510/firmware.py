"""Vendor firmware files the SDK looks for.

``libdtvdev-sdk.so`` logs ``LYDEV_DownLoadFileForLME251X(rom.dat)`` and
``LYDEV_DownLoadFileForLME251X(fw.bin)`` and names ``ISDBT.20160118``. It looks
the files up by name; none has been found embedded in the APK so far (the
SDK's ``.data`` holds channel tables, not an image). If the device turns out to
need an upload, these files go in ``firmware/`` in this tree.

Nothing is committed to a public snapshot from here: the release procedure
withholds ``firmware/`` and ``*.bin``/``*.dat``.
"""
import hashlib
import os

ROOT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                    "firmware")
NAMES = ("rom.dat", "fw.bin")


def path(name):
    return os.path.join(ROOT, name)


def available():
    return {name: path(name) for name in NAMES if os.path.exists(path(name))}


def load(name):
    """Bytes of one firmware file, or None when it is not in the tree."""
    if name not in NAMES:
        raise ValueError("unknown firmware file: %s" % name)
    if not os.path.exists(path(name)):
        return None
    with open(path(name), "rb") as handle:
        return handle.read()


def digest(name):
    data = load(name)
    return hashlib.sha256(data).hexdigest() if data is not None else None


def summary():
    out = {}
    for name in NAMES:
        out[name] = {
            "present": os.path.exists(path(name)),
            "sha256": digest(name),
            "bytes": len(load(name)) if os.path.exists(path(name)) else 0,
        }
    return out
