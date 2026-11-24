"""Bring-up for the LME2510C, as far as the APK lets us go without the stick.

The vendor sequence is not transcribed yet. What the APK proves (2026-11-06):

- Java opens the device by vendor id ``0x3344`` and passes the file descriptor
  to ``probdevice(fd, mode)``;
- the SDK then may download ``fw.bin`` / ``rom.dat`` through
  ``LYDEV_DownLoadFileForLME251X`` — those are *file names it looks for*; no
  such blob has been found embedded in the APK yet;
- the runtime API is ``setfrequency(fd, kHz)``, ``filtersetting(fd, pids)``,
  ``readtsdata(fd)`` and ``deletedevice(fd)``;
- ``getversion()`` returns ``"LME SDK V1.6.isdbt "`` — the ISDB-T variant.
"""
# The cold-download frames and the vendor firmware images are withheld from a
# public snapshot: only the hardware needs them, and they are the vendor*s* payload.
try:
    from . import firmware, sequence
except ImportError:          # a public snapshot keeps neither
    firmware = sequence = None

from .transport import TS_READ, PacketAligner, TransportError


class Lme2510Device:
    """An open LME2510C handle plus the bookkeeping the driver needs."""

    SDK_VERSION = "LME SDK V1.6.isdbt"

    def __init__(self, device, log=None):
        self.device = device
        self.log = log or (lambda message: None)
        self.aligner = PacketAligner()
        self.opened = False

    # -- lifecycle ---------------------------------------------------------
    def open(self):
        self.device.open()
        self.opened = True
        return self

    def close(self):
        if self.opened:
            self.device.close()
            self.opened = False

    # -- information -------------------------------------------------------
    def describe(self):
        usb = self.device.usb
        info = {
            "vendor": usb.idVendor,
            "product": usb.idProduct,
            "bus": getattr(usb, "bus", None),
            "address": getattr(usb, "address", None),
            "manufacturer": _string(usb, "manufacturer"),
            "product_string": _string(usb, "product"),
            "serial": _string(usb, "serial_number"),
            "sdk": self.SDK_VERSION,
            "endpoints": {},
        }
        if self.opened:
            info["endpoints"] = self.device.endpoint_map()
        return info

    def probe(self):
        """Open, claim, report. Reads nothing until the init sequence is known."""
        self.open()
        info = self.describe()
        info["firmware"] = firmware.summary() if firmware else "withheld in this snapshot"
        return info

    # -- vendor init -------------------------------------------------------
    def start(self):
        """Open and replay the captured vendor init sequence (hardware only).

        The frames are the vendor SDK's own output, captured under emulation
        (see. The last frame (0x8a 0x00) resets the MCU: the
        device is expected to disconnect and re-enumerate afterwards, so the
        caller must not reuse this handle for the next phase.

        The cold frames are the vendor's firmware upload; a public snapshot does
        not carry them, so this raises there instead of failing obscurely.
        """
        if sequence is None:
            raise RuntimeError(
                "the cold-download frames are withheld from this snapshot; the "
                "warm path in lme2510.driver does not need them")
        self.open()
        try:
            reply = self.device.control_in(*sequence.CONTROL_STRING)
            self.log("string descriptor: %s" % reply[:8].hex())
        except TransportError as exc:
            self.log("string descriptor failed: %s" % exc)

        for index, frame_hex in enumerate(sequence.FRAMES):
            frame = bytes.fromhex(frame_hex)
            self.device.write(frame, sequence.EP_OUT)
            ack = self.device.read(sequence.ACK_BYTES, sequence.EP_IN)
            if ack and ack[0] != 0x00:
                self.log("frame %d (cmd 0x%02x): ack 0x%02x"
                         % (index, frame[0], ack[0]))
        self.log("vendor init replayed: %d frames, MCU reset sent"
                 % len(sequence.FRAMES))
        return self

    # -- stream ------------------------------------------------------------
    def read_ts(self, size=TS_READ, timeout=300):
        try:
            return self.aligner.push(self.device.read_ts(size, timeout=timeout))
        except TransportError:
            return b""


def _string(usb, name):
    try:
        value = getattr(usb, name, None)
    except Exception:
        return None
    return value

