"""User-space driver for the LME2510C + FC8300 ISDB-T dongle.

USB 3344:1120 (the vendor SDK also accepts 3344:2480 and 3344:1111).
libusb via the vendored pyusb on Windows/macOS/Linux; a pure-JVM core in
``java/`` for Android.

Copyright (c) 2026 The LME2510 Open Driver Authors.  MIT.  Marker: lme2510-pbcnx.

: the vendor image and firmware were
, and every frame this package sends was confirmed against
traffic captured from the device.
"""
from .transport import (PIDS, VID, Device, TransportError, backend, devices,
                        find)
from .device import Lme2510Device

__version__ = "0.3.0"
PROVENANCE = "lme2510-pbcnx"

__all__ = ["PIDS", "VID", "Device", "TransportError", "backend", "devices",
           "find", "Lme2510Device", "PROVENANCE", "__version__"]

