"""The VCO band calibration, transcribed: `FUN_00019558` (0x9558).

Tuner Init calls this once (column 0) and every `fc8300b_set_freq` calls it again
with the chip's status byte as the column selector.  It is a search over four VCO
columns:

  0x71..0x74 <- the column's four bytes
  0x5f = 0x40, 0x6d = 0x0f, 0x6e = 0x1f, 0x6f = 0x3f, 0x6a = 4, 0x6a = 0x14
  0x75 = 0x30                     start a measurement
  poll `0x76` up to ten times, 1 ms apart, until it answers 0x20
  read `0x76` after 0x75 = 0x34, 0x35, 0x36, 0x37   (the four sub-band results)
  if the first three are all non-zero: 0x5f = 0x00, done
  otherwise the next column (up to four)
  all four failed: 0x6a = 0x15, 0x70 = 0x7f

The status-byte selector is `0x0f -> 1`, `0x10 -> 2`, `0x21 -> 3`; anything
above 3 skips the search and stamps the failure.  The column bytes are the
16-byte table at ELF `0x1cf82`, read as t[0+p], t[4+p], t[8+p], t[12+p].

**A read that answers something unexpected is still a valid result.**  The four
sub-band reads only have to be non-zero; on 2026-11-15 a replay demanded the
capture's exact values, repeated a read 92 times, and destroyed the measurement
by walking the chip's window §9).
"""
from lme2510 import replay

TUNER = 0xB0
PAGE = 0x0F00

READY_VALUE = 0x20
SUB_BANDS = (0x34, 0x35, 0x36, 0x37)
COLUMNS = (
    (0xBB, 0xB3, 0x32, 0x0F),
    (0xBB, 0xBB, 0x43, 0x10),
    (0xBB, 0xBB, 0x54, 0x21),
    (0xBB, 0xBB, 0x65, 0x32),
)
SELECTOR = {0x0F: 1, 0x10: 2, 0x21: 3}
FAIL_REGISTER = 0x70
FAIL_VALUE = 0x7F
SUCCESS_REGISTER = 0x5F


def write(register, value):
    """A calibration register write, as it appears on the wire."""
    return replay.write_frame(PAGE | register, value, device=TUNER)


def run(send, read, selector=0, wait=None):
    """Run the search; returns ``(locked, column_used)``.

    ``selector`` is the status byte the caller read from `0x74`: 0 from Tuner Init,
    or the chip's status byte in a tune (`0x0f`/`0x10`/`0x21` -> 1/2/3).
    """
    wait = wait or (lambda: None)
    if selector in SELECTOR:
        columns = list(COLUMNS)[SELECTOR[selector]:]
    elif selector > 3 or selector not in (0,):
        columns = []
    else:
        columns = list(COLUMNS)

    for column in columns:
        for register, value in zip((0x71, 0x72, 0x73, 0x74), column):
            send(write(register, value))
        for register, value in ((0x5F, 0x40), (0x6D, 0x0F), (0x6E, 0x1F),
                                (0x6F, 0x3F), (0x6A, 0x04), (0x6A, 0x14)):
            send(write(register, value))
        send(write(0x75, 0x30))
        for _ in range(10):
            wait()
            ready = read(0x0F76, 1)
            if ready and ready[0] == READY_VALUE:
                break
        results = []
        for sub in SUB_BANDS:
            send(write(0x75, sub))
            results.append(read(0x0F76, 1))
        first_three = [value[0] if value else 0 for value in results[:3]]
        if all(first_three):
            send(write(SUCCESS_REGISTER, 0x00))
            return True, column
    if not columns:
        send(write(0x6A, 0x15))
    send(write(FAIL_REGISTER, FAIL_VALUE))
    return False, None
