"""The set-frequency burst, generated from the transcribed vendor logic.

The captured tail (`lme2510/vendor_trace.py`, frames 330..) holds only **27
writes**; everything else in it is polling.  Those 27 lines are the whole tune,
and each one is explained by the C:

  1  `0x0013 <- read & 0x7f`   `tuner_set_freq` clears the shutdown bit (0xb0)
  2  `0x3011 <- 0x16`          bandwidth code 22 = 6 MHz (0xbe)
  3  `0x0fe9 <- byteA`         the band plan (lme2510/bandplan.py)
  4  `0x0f52 <- byteB`         the band plan
  5  `0x0f1f <- 0x79`          the band-4 handler's last write
  6  `0x0f1c <- IF value`      clamp(table[group] + peak - 7, 0x10, 0x1f)
  7  `0x0f75 <- 0x31`          FUN_00019e84: start the PLL block
  8  `0x0f77 <- 0x00`
  9..14  the PLL word          `lme2510/fc8300.py` (0x0f51/57/5a/50/50/58/59)
  15..17 `0x0f77 <- 0x02` + the `0x0f50` strobe, only when `0x76 < 0x3e`
  18..22 the tail              `0x0f75/0x0f78/0x0f04` twice (0xbe then 0xb0)
  23..24 `0x0001 <- 0x7f, 0xcf`  (0xbe)
  25..26 `0x0001 <- 0xff`, `0x0013 <- 0x80`  (0xb0)

`set_frequency_frames(freq)` reproduces those 27 frames byte for byte at 485143,
which `tests/test_tune.py` checks against the capture.  What it does *not* do yet
is the polling (reads of 0x64/0x65/0x76 and the VCO calibration) -- that is the
next transcription, and `lme2510/lockcheck.py` is the piece that follows it.
"""
from lme2510 import bandplan, fc8300, replay

TUNER = 0xB0
DEMOD = 0xBE

BANDWIDTH_CODE = 0x16          # 0x3011: 22 = 6 MHz ISDB-T
BAND_SELECT = 0x0F1F
BAND_SELECT_VALUE = 0x79
IF_REGISTER = 0x0F1C
IF_MIN, IF_MAX = 0x10, 0x1F
SWEEP_OFFSET = 7               # Tuner Init stores the 0f1c sweep peak minus 7

# `fc8300b_set_freq`'s chain of comparisons, and the value each segment assigns.
# Below the first edge the code sets 0x0b, which indexes one past the table and
# is why `if_base` is 11 - group (the table at 0x19cee is 11,10,9,...,1 and the
# next slot is 0).
GROUP_EDGES = (469488, 485000, 496999, 515000, 533000, 581000, 605000, 635000,
               671000, 737000, 803000)
GROUP_VALUES = (0x0B, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9)


def frequency_group(freq_khz):
    """The `iVar5` the vendor's chain of comparisons assigns."""
    for edge, value in zip(GROUP_EDGES, GROUP_VALUES):
        if freq_khz < edge:
            return value
    return 10


def if_base(group):
    """The table value for a group: 11, 10, 9, ... (0 for the 0x0b segment)."""
    return max(0, 11 - group)


def if_value(freq_khz, peak=0x16):
    """`clamp(table[group] + (peak - 7), 0x10, 0x1f)`."""
    value = if_base(frequency_group(freq_khz)) + (peak - SWEEP_OFFSET)
    return max(IF_MIN, min(IF_MAX, value))


def set_frequency_frames(freq_khz, peak=0x16, shutdown=0x00, bandwidth=BANDWIDTH_CODE):
    check_frequency(freq_khz)
    """The 27 writes of a set-frequency, in wire order, as frames.

    ``peak`` is the `0f1c` sweep result Tuner Init stored (0x16 on the captured
    device), ``shutdown`` the value `0x0013` is left at (the capture has 0x00
    after the clear and 0x80 in the final write).
    """
    plan = bandplan.plan(freq_khz)
    if plan is None:
        raise ValueError("no band-plan record within 3000 kHz of %d" % freq_khz)
    byte_a, byte_b = plan
    word = fc8300.synth_word(freq_khz)["registers"]
    frames = [
        replay.write_frame(0x0013, shutdown, device=TUNER),
        replay.write_frame(0x3011, bandwidth, device=DEMOD),
        replay.write_frame(0x0FE9, byte_a, device=DEMOD),
        replay.write_frame(0x0F52, byte_b, device=DEMOD),
        replay.write_frame(BAND_SELECT, BAND_SELECT_VALUE, device=DEMOD),
        replay.write_frame(IF_REGISTER, if_value(freq_khz, peak), device=TUNER),
        replay.write_frame(0x0F75, 0x31, device=TUNER),
        replay.write_frame(0x0F77, 0x00, device=TUNER),
        replay.write_frame(0x0F51, word[0x0F51], device=TUNER),
        replay.write_frame(0x0F57, word[0x0F57], device=TUNER),
        replay.write_frame(0x0F5A, word[0x0F5A], device=TUNER),
        replay.write_frame(0x0F50, 0xE3, device=TUNER),
        replay.write_frame(0x0F50, 0xFF, device=TUNER),
        replay.write_frame(0x0F58, word[0x0F58], device=TUNER),
        replay.write_frame(0x0F59, word[0x0F59], device=TUNER),
        replay.write_frame(0x0F77, 0x02, device=TUNER),
        replay.write_frame(0x0F50, 0xE3, device=TUNER),
        replay.write_frame(0x0F50, 0xFF, device=TUNER),
        replay.write_frame(0x0F75, 0x31, device=DEMOD),
        replay.write_frame(0x0F78, 0xF1, device=DEMOD),
        replay.write_frame(0x0F04, 0x12, device=DEMOD),
        replay.write_frame(0x0F78, 0x31, device=DEMOD),
        replay.write_frame(0x0F04, 0x10, device=DEMOD),
        replay.write_frame(0x0001, 0x7F, device=DEMOD),
        replay.write_frame(0x0001, 0xCF, device=DEMOD),
        replay.write_frame(0x0001, 0xFF, device=TUNER),
        replay.write_frame(0x0013, 0x80, device=TUNER),
    ]
    return frames


BEFORE_POLL = 15     # writes 0..14 come before the 0x64/0x65/0x76 reads
CONDITIONAL = 3      # 0x0f77 = 2, 0x0f50 = e3, 0x0f50 = ff
CONDITION_LIMIT = 0x3E   # the vendor writes the block when 0x76 < 0x3e


def check_frequency(freq_khz):
    """Refuse a frequency outside the FC8300's specified range.

    The vendor's table carries one record at 809143 kHz, 3.1 MHz above the
    datasheet's 806 MHz ceiling. Tuning it is not
    proven and could be outside the part's spec, so the driver says so instead of
    sending the burst.
    """
    from lme2510 import bandplan
    if not bandplan.within_chip(freq_khz):
        raise ValueError("%d kHz is outside the FC8300 range %d..%d kHz"
                         % (freq_khz, bandplan.CHIP_MIN_KHZ, bandplan.CHIP_MAX_KHZ))


def run(send, read, freq_khz, peak=0x16, wait=None):
    """Set the frequency by driving the device through two callables.

    This is the driver's own set-frequency, not a replay: ``send(frame)`` writes
    one frame, ``read(register, count)`` returns the payload of a read (the bytes
    after the `0x55` marker, or ``b""``).

    Order, from the captured tail and the C:

      1. read `0x0013`, write it back with bit 7 cleared (out of shutdown)
      2. read `0x0055` (two bytes; the vendor reads it and ignores it)
      3. the first 15 writes (band plan, `0x1c`, word, strobe)
      4. read `0x64`, `0x65` -- retried up to four times -- and `0x76`
      5. if `0x76 < 0x3e`: `0x0f77 = 2` and the `0x0f50` strobe
      6. the last nine writes (the `0x0f78`/`0x0f04` tail and the closing pair)

    Returns the `0x76` byte that decided the conditional (or None).
    """
    check_frequency(freq_khz)
    wait = wait or (lambda: None)
    first = read(0x0013, 1)
    send(replay.write_frame(0x0013, (first[0] & 0x7F) if first else 0x00,
                            device=TUNER))
    read(0x0055, 2)

    frames = set_frequency_frames(freq_khz, peak)
    # frames[0] is the 0x0013 write, which needs the read above; send 1..14 here.
    for frame in frames[1:BEFORE_POLL]:
        send(frame)

    limit = BEFORE_POLL + CONDITIONAL
    word_76 = None
    for _attempt in range(4):
        status = read(0x0F64, 1)
        quality = read(0x0F65, 1)
        if status and (status[0] & 0x11) == 0x11 and quality and quality[0] > 1:
            word_76 = read(0x0F76, 1)
            break
        wait()
    if word_76 is None:
        word_76 = read(0x0F76, 1)
    if word_76 and word_76[0] < CONDITION_LIMIT:
        for frame in frames[BEFORE_POLL:limit]:
            send(frame)
    for frame in frames[limit:]:
        send(frame)
    return word_76[0] if word_76 else None



