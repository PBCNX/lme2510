"""FC8300B register-level pieces, transcribed from the vendor image.

Only what is proven is here.  The synthesizer word below is a transcription of
``fc8300b_set_freq``'s unnamed helper (image offset ``0x9e84``), which builds the
four registers every tune writes; it is checked bit-exactly against two captured
channels (``tests/test_fc8300.py``, see `` §8).

Provenance: static read of ``libdtvdev-sdk.so`` 5.08/5.15 + two emulator runs and
two hardware captures, 2026-11-14.  Nothing in this module talks to a device.
"""

# The two constants the image uses.  0xA151C331 is the reciprocal it multiplies
# by instead of dividing by 26,000 (the reference in kHz), and 0x6590 = 26000.
RECIPROCAL = 0xA151C331
REFERENCE_KHZ = 26000

# The prescaler table at image vaddr 0x1001CF92.
PRESCALERS = (8, 12, 16, 24, 32, 48, 64, 96)

# The VCO ceiling the prescaler is chosen against (0x5B8D80).
VCO_CEILING_KHZ = 6_000_000

REG_PRESCALER = 0x0F51
REG_WORD = (0x0F57, 0x0F5A, 0x0F58, 0x0F59)


def prescaler(freq_khz):
    """Largest table entry with ``freq * m <= 6 GHz``, and its index.

    The image walks the other way (``freq*m > 6e6`` steps the index on) and its
    chain ends at ``m = 8`` for anything above 750 MHz, above the ceiling. Both
    select m = 12 for 479143 and 485143 kHz; the low end clamps at 96.
    """
    for index in range(len(PRESCALERS) - 1, -1, -1):
        if freq_khz * PRESCALERS[index] <= VCO_CEILING_KHZ:
            return PRESCALERS[index], index
    return PRESCALERS[0], 0


def synth_word(freq_khz):
    """Return the register writes one tune sends for ``freq_khz``.

    ``q`` is the integer part of the divider and ``t`` its 16-bit fraction, both
    against the 26 MHz reference: ``VCO = 26 MHz * (q + t/65536)``, with the VCO
    divided by ``m`` to reach the channel.  The shape matches the four frames
    observed on the wire: ``0f57``, ``0f5a``, ``0f58``, ``0f59``, in that order.
    """
    multiplier, index = prescaler(freq_khz)
    x = freq_khz * multiplier
    high = (x * RECIPROCAL) >> 32
    q = high >> 14
    remainder = x - REFERENCE_KHZ * q
    fraction = (((remainder << 16) & 0xFFFFFFFF) * RECIPROCAL) >> 32

    values = {
        0x0F51: (2 * index + 1) & 0xFF,          # the prescaler code
        0x0F57: (((high >> 17) & 0x20) | (fraction >> 26)) & 0xFF,
        0x0F5A: q & 0xFF,
        0x0F58: (fraction >> 18) & 0xFF,
        0x0F59: ((fraction >> 10) | 1) & 0xFF,
    }
    return {
        "multiplier": multiplier,
        "vco_khz": x,
        "divider": q,
        "fraction": fraction,
        "registers": values,
        "writes": [(register, values[register]) for register in
                   (REG_PRESCALER,) + REG_WORD],
    }


def word_bytes(freq_khz):
    """The four word bytes in the order the tune sends them: 57, 5a, 58, 59."""
    writes = dict(synth_word(freq_khz)["writes"])
    return bytes(writes[register] for register in REG_WORD)
