"""USB transport for the LME2510C + FC8300 ISDB-T dongle.

The device is opened by vendor id ``0x3344``. Everything below is measured from
the vendor APK's SDK, not yet from the stick: ``lmedtvsdk.F_GetDeviceFD()``
opens ``usbDevice.getVendorId() == 13124`` and hands the file descriptor to the
native SDK, and ``libdtvdev-sdk.so`` carries descriptor templates for
``3344:1111`` and ``3344:1120`` (see ``).

The template endpoints are bulk ``0x01``/``0x02`` out, bulk ``0x81``/``0x86``
in, iso ``0x87`` in and interrupt ``0x8a`` in. The real device decides; the
transport discovers them from the active configuration instead of assuming.
"""
import os
import sys

VID = 0x3344
PIDS = (0x1120, 0x2480, 0x1111)

DEFAULT_TIMEOUT = 2000
TS_PACKET = 188
TS_READ = TS_PACKET * 80          # 15,040 B, the app's batch size


class TransportError(RuntimeError):
    pass


def _pyusb():
    """Put the vendored pyusb on sys.path, then import it."""
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    vendor = os.path.join(root, "vendor")
    if os.path.isdir(os.path.join(vendor, "usb")) and vendor not in sys.path:
        sys.path.insert(0, vendor)
    import usb.util

    return usb.util


def backend():
    """libusb backend: the DLL named by LME2510_LIBUSB first, then the system one.

    The Windows DLL is NOT shipped in this repository (owner, 2026-11-24): get it from libusb.info and point
    LME2510_LIBUSB at it, exactly as the README and the user guide say.
    """
    _pyusb()
    import usb.backend.libusb1 as libusb1

    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    bundled = os.path.join(root, "vendor", "windows", "x64", "libusb-1.0.dll")
    chosen = os.environ.get("LME2510_LIBUSB")
    if chosen:
        return libusb1.get_backend(find_library=lambda name: chosen)
    if os.name == "nt" and os.path.exists(bundled):
        return libusb1.get_backend(find_library=lambda name: bundled)
    return libusb1.get_backend()


def devices(pid=None):
    """All LME devices on the bus; filter by product id when given."""
    _pyusb()
    import usb.core

    handle = backend()
    if handle is None:
        raise TransportError(
            "libusb is not available: install libusb-1.0 from https://libusb.info and set LME2510_LIBUSB to the\n"
            "full path of libusb-1.0.dll (or put the DLL next to python.exe)")
    found = list(usb.core.find(find_all=True, idVendor=VID, backend=handle))
    if pid is not None:
        return [d for d in found if d.idProduct == pid]
    return [d for d in found if d.idProduct in PIDS] or found


def find(pid=None):
    found = devices(pid)
    return found[0] if found else None


class Device:
    """One open LME2510C handle."""

    def __init__(self, usb_device, timeout=DEFAULT_TIMEOUT, log=None):
        self.usb = usb_device
        self.timeout = timeout
        self.log = log or (lambda message: None)
        self.configuration = None
        self.interface = None
        self.endpoints = {}

    # -- lifecycle ---------------------------------------------------------
    def open(self):
        try:
            self.usb.set_configuration()
        except Exception:
            pass
        self.configuration = self.usb.get_active_configuration()
        # The data endpoints are not on alternate setting 0: this device puts the
        # two bulk pipes on alt 1 (the vendor's own code selects alt 1 as well).
        # Try the altsettings this device has, and keep the first one that carries
        # bulk endpoints, so a firmware that renumbers them still works.
        interface, alternate, chosen = 0, 0, []
        for candidate_interface, candidate_alternate in ((0, 1), (0, 0), (0, 2)):
            try:
                candidate = self.configuration[(candidate_interface,
                                                candidate_alternate)]
            except Exception:  # noqa: BLE001
                continue
            bulk = [endpoint for endpoint in candidate
                    if (endpoint.bmAttributes & 0x03) == 2]
            if bulk:
                interface, alternate, chosen = (candidate_interface,
                                                candidate_alternate, bulk)
                break
        if not chosen:                      # no bulk at all: take alt 0 as it is
            interface, alternate = 0, 0
            chosen = list(self.configuration[(0, 0)])
        self.interface = self.configuration[(interface, alternate)]
        self._interface_number = interface
        _pyusb().claim_interface(self.usb, interface)
        if alternate:
            self.usb.set_interface_altsetting(interface=interface,
                                              alternate_setting=alternate)
        for endpoint in chosen:
            self.endpoints[endpoint.bEndpointAddress] = endpoint
        return self

    def close(self):
        """Release the interface **and close the libusb handle**. Returns False on a real failure.

        Releasing the interface is not closing the device: pyusb opens the handle lazily and keeps it open
        until the resources are disposed. Measured on Windows 2026-11-20 — the recovery ladder closed the
        handle, reset the chip, and then the re-open failed with `[Errno 13] Access denied` for as long as we
        retried, because WinUSB allows one handle at a time and ours was still open. A fresh process opened
        the device immediately, which is what pointed at this. So the dispose is part of the close.

        A close *after the device left the bus* legitimately fails with a no-device error, and that is not
        news: the detach line already said so. Anything else is recorded and reported, because a swallowed
        close error is how cleanup hides the bug it was cleaning up after (IT9308, 2026-11-20).
        """
        util = _pyusb()
        failed = None
        try:
            util.release_interface(self.usb, getattr(self, "_interface_number", 0))
        except Exception as error:  # noqa: BLE001
            failed = error               # judged below: a departed device is not a failure
        try:
            # The vendor's own teardown does this too (`libusb_release_interface`, then `libusb_close`) —
            # see §7. Without it the OS still sees our handle.
            util.dispose_resources(self.usb)
        except Exception as error:  # noqa: BLE001
            failed = failed or error
        if failed is None:
            return True
        # libusb spells the no-device case both ways depending on the backend: "LIBUSB_ERROR_NO_DEVICE",
        # "no such device", "device not found". Normalise the separators before matching.
        text = str(failed).lower().replace("_", " ").replace("-", " ")
        if "no device" in text or "no such device" in text or "not found" in text or "gone" in text:
            return True                  # the device is already away; nothing left to release
        self.close_error = failed
        self.log("close failed: %s" % failed)
        return False

    # -- recovery ----------------------------------------------------------
    def clear_halt(self, addresses=None, transfer=None):
        """CLEAR_FEATURE(ENDPOINT_HALT) on every endpoint; returns ``(cleared, failed)``.

        A halted endpoint survives a close/reopen, because the firmware's RAM survives a bus reset
        (SMSRIO, 2026-11-20 — and it is the third candidate for our own 485143 "locked, no data"). So a
        rebuild clears the halts on the *fresh* handle rather than trusting the new one to be clean.

        Deliberately **not** `libusb_reset_device`: IT9308 measured it harmful on their bridge and SMSRIO
        the same class of failure, because a USB reset is not a device reset. A clear-feature is per
        endpoint, cheap and reversible, which is why it is the tool here. The vendored pyusb predates
        `usb.util.clear_halt`, so the transfer libusb would send is sent directly — same request, every
        version. `transfer` is injectable so the sequencing can be tested without a device.
        """
        addresses = list(self.endpoints) if addresses is None else list(addresses)
        if transfer is None:
            def transfer(address):
                # bmRequestType 0x02 (host-to-device, endpoint), bRequest CLEAR_FEATURE,
                # wValue ENDPOINT_HALT (0), wIndex the endpoint address, no data
                self.usb.ctrl_transfer(0x02, 0x01, 0x00, address, 0, DEFAULT_TIMEOUT)
        cleared, failed = [], []
        for address in addresses:
            try:
                transfer(address)
                cleared.append(address)
            except Exception as error:  # noqa: BLE001
                failed.append((address, error))
        return cleared, failed

    def identity(self):
        """``(product_id, bus, address)``: the three numbers that say which node this is.

        A reset's shape is **per host, not per chip** (IT9175 and IT9308, 2026-11-20): the same bare reset
        kept the address on Windows and re-enumerated on macOS, on both bridges. So no message may say
        "re-enumerated" unless it read the address — it prints this pair instead, and the reader decides.
        """
        return (self.usb.idProduct, self.usb.bus, self.usb.address)

    def identity_line(self):
        product, bus, address = self.identity()
        return "%04x:%04x bus %s addr %s" % (VID, product, bus, address)

    # -- endpoints ---------------------------------------------------------
    def endpoint_map(self):
        """Address -> (direction, transfer type, max packet)."""
        out = {}
        for address, endpoint in self.endpoints.items():
            out[address] = (
                "in" if address & 0x80 else "out",
                endpoint.bmAttributes & 0x03,
                endpoint.wMaxPacketSize,
            )
        return out

    def _pick(self, direction, bulk=True):
        for address, endpoint in self.endpoints.items():
            if direction == "in" and not address & 0x80:
                continue
            if direction == "out" and address & 0x80:
                continue
            if bulk and (endpoint.bmAttributes & 0x03) != 2:
                continue
            return endpoint
        raise TransportError("no %s endpoint on interface 0" % direction)

    def ts_endpoint(self):
        """The bulk IN endpoint that carries MPEG-TS on this unit.

        It is **not always 0x88**: the warm unit on the Pi served 0x88, while the
        same stick in its cold identity on Windows offers 0x86 (bulk, 512) and no
        0x88 at all. So prefer 0x88 when it exists (the vendor's own choice) and
        otherwise take the largest bulk IN that is not the command endpoint 0x81.
        """
        if 0x88 in self.endpoints:
            return 0x88
        best = None
        for address, endpoint in self.endpoints.items():
            if not address & 0x80:
                continue
            if (endpoint.bmAttributes & 0x03) != 2:
                continue
            if address == 0x81:
                continue
            if best is None or endpoint.wMaxPacketSize > best[0]:
                best = (endpoint.wMaxPacketSize, address)
        return best[1] if best else None

    # -- transfers ---------------------------------------------------------
    def _resolve(self, endpoint, direction):
        if endpoint is None:
            return self._pick(direction)
        if isinstance(endpoint, int):
            found = self.endpoints.get(endpoint)
            if found is None:
                raise TransportError("no endpoint 0x%02x on interface 0"
                                     % endpoint)
            return found
        return endpoint

    def write(self, data, endpoint=None):
        endpoint = self._resolve(endpoint, "out")
        sent = self.usb.write(endpoint.bEndpointAddress, data, self.timeout)
        self.log("out 0x%02x %4d bytes" % (endpoint.bEndpointAddress, len(data)))
        return sent

    def read(self, size, endpoint=None, timeout=None):
        endpoint = self._resolve(endpoint, "in")
        data = bytes(self.usb.read(endpoint.bEndpointAddress, size,
                                   timeout or self.timeout))
        self.log("in  0x%02x %4d bytes" % (endpoint.bEndpointAddress, len(data)))
        return data

    def control_in(self, request_type, request, value, index, length):
        data = bytes(self.usb.ctrl_transfer(request_type, request, value,
                                           index, length, self.timeout))
        self.log("ctrl %02x req=%02x val=%04x len=%d"
                 % (request_type, request, value, length))
        return data

    def read_ts(self, size=TS_READ, timeout=300):
        """One TS batch. Only meaningful after the vendor init sequence, which
        is not yet transcribed — see"""
        return self.read(size, timeout=timeout)


class PacketAligner:
    """Find the 0x47 phase and return only complete 188-byte packets."""

    def __init__(self):
        self.buffer = bytearray()

    def push(self, data):
        self.buffer += data
        if not self.buffer:
            return b""
        offset = 0
        while offset < TS_PACKET:
            available = len(self.buffer) - offset
            if available >= 2 * TS_PACKET:
                if (self.buffer[offset] == 0x47
                        and self.buffer[offset + TS_PACKET] == 0x47):
                    break
                offset += 1
            elif available >= TS_PACKET:
                if self.buffer[offset] == 0x47:
                    break
                offset += 1
            else:
                return b""
        if offset >= TS_PACKET:
            self.buffer = self.buffer[-2 * TS_PACKET:]
            return b""
        body = self.buffer[offset:]
        count = len(body) // TS_PACKET * TS_PACKET
        if not count:
            self.buffer = body
            return b""
        packets = bytes(body[:count])
        self.buffer = body[count:]
        for start in range(0, len(packets), TS_PACKET):
            if packets[start] != 0x47:
                self.buffer = bytearray()
                return b""
        return packets

