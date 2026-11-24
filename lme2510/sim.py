"""A device that answers like the captured LME2510, so the driver can be tested
without hardware, on any platform.

It is not a model of the RF: it is a model of the *bus answers*. A write ACKs with
`0x88`; a read answers `0x55` plus the payload. The values it gives for the
calibration, the `0f1c` sweep and the lock scan are the ones the hardware device gave
on 2026-11-15, so a run through it exercises the same branches a real run takes.

    from lme2510 import driver, sim
    transport = sim.SimulatedTransport()
    core = driver.Driver(transport)
    core.init(); core.tune(479143); locked, _ = core.scan()
"""
ACK = b"\x88"
REPLY = 0x55

# what the captured unit answered
CALIBRATION_READY = 0x20
SUB_BANDS = {0x34: 0x16, 0x35: 0x18, 0x36: 0x19, 0x37: 0x1F}
SWEEP = (0x94, 0x9A, 0xA1, 0xA6, 0xA3, 0x9E, 0x96, 0x91, 0x8C)
LOCK = {0x3025: 0x5F, 0x3026: 0x17, 0x2023: 0x00, 0x50C5: 0x0A}


class SimulatedTransport:
    def __init__(self):
        self.selected = None
        self.last_write = None
        self.sent = []

    def exchange(self, frame):
        """One frame in, one answer out — the whole transport contract."""
        self.sent.append(frame)
        if frame[:2] == b"\x04\x03" and len(frame) == 5:
            self.selected = (frame[3] << 8) | frame[4]
            return ACK
        if frame[:1] == b"\x86" and len(frame) == 5:
            count = frame[3]
            return bytes([REPLY]) + self._payload(count)
        if len(frame) == 6 and frame[:2] == b"\x04\x04":
            self.last_write = ((frame[3] << 8) | frame[4], frame[5])
            return ACK
        return ACK

    def _payload(self, count):
        value = 0
        register = self.selected
        if register == 0x0F76:
            # the calibration: the poll after 0x75=0x30, the sub-bands after 34..37
            if self.last_write and self.last_write[0] == 0x0F75:
                value = SUB_BANDS.get(self.last_write[1], CALIBRATION_READY)
            else:
                value = CALIBRATION_READY
        elif register == 0x0FF5:
            step = self.last_write[1] if self.last_write and self.last_write[0] == 0x0F1C else 0x13
            index = min(len(SWEEP) - 1, max(0, step - 0x13))
            value = SWEEP[index]
        else:
            value = LOCK.get(register, 0x00)
        return bytes([value] * count)

    def writes(self):
        """The write frames sent, as (register, value) pairs."""
        out = []
        for frame in self.sent:
            if len(frame) == 6 and frame[:2] == b"\x04\x04":
                out.append(((frame[3] << 8) | frame[4], frame[5]))
        return out
