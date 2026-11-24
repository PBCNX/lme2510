"""The tuner band plan: one 12-byte record per channel (TRANSCRIBED).

Read out of the vendor library at run time: `fc8300b_set_freq` (0x9818) takes
the band-4 branch to 0x98da, which walks these records at (solution + 0x840)
for the entry within +-3000 kHz of the wanted frequency and writes
`byteA` to register 0xe9 and `byteB` to register 0x52.  The table is built
during Tuner Init, so it is not in the .so on disk -- it was dumped from an
emulated warm init, 57 records.

Check: 485143 kHz must select byteA=0x70 byteB=0x00, which is what the
captured tune writes (`0404be0fe970`, `0404be0f5200`).
"""

TOLERANCE_KHZ = 3000

RECORDS = (
    (473143, 0x70, 0x40),
    (479143, 0x70, 0x00),
    (485143, 0x70, 0x00),
    (491143, 0x70, 0x00),
    (497143, 0x70, 0x00),
    (503143, 0x6f, 0x00),
    (509143, 0x6f, 0x00),
    (515143, 0x6f, 0x00),
    (521143, 0x6f, 0x40),
    (527143, 0x6f, 0x00),
    (533143, 0x6f, 0x00),
    (539143, 0x6f, 0x00),
    (545143, 0x6f, 0x70),
    (551143, 0x6f, 0x30),
    (557143, 0x6f, 0x00),
    (563143, 0x70, 0x00),
    (569143, 0x70, 0x30),
    (575143, 0x70, 0x30),
    (581143, 0x70, 0x00),
    (587143, 0x71, 0x00),
    (593143, 0x72, 0x30),
    (599143, 0x72, 0x30),
    (605143, 0x72, 0x30),
    (611143, 0x73, 0x30),
    (617143, 0x73, 0x00),
    (623143, 0x74, 0x20),
    (629143, 0x74, 0x30),
    (635143, 0x73, 0x00),
    (641143, 0x74, 0x00),
    (647143, 0x74, 0x30),
    (653143, 0x74, 0x50),
    (659143, 0x73, 0x00),
    (665143, 0x74, 0x00),
    (671143, 0x74, 0x70),
    (677143, 0x74, 0x40),
    (683143, 0x73, 0x00),
    (689143, 0x73, 0x00),
    (695143, 0x73, 0x00),
    (701143, 0x73, 0x40),
    (707143, 0x72, 0x50),
    (713143, 0x71, 0x50),
    (719143, 0x71, 0x50),
    (725143, 0x71, 0x50),
    (731143, 0x70, 0x20),
    (737143, 0x70, 0x50),
    (743143, 0x70, 0x50),
    (749143, 0x6f, 0x40),
    (755143, 0x6f, 0x30),
    (761143, 0x6f, 0x70),
    (767143, 0x6f, 0x20),
    (773143, 0x6e, 0x20),
    (779143, 0x6e, 0x10),
    (785143, 0x6e, 0x40),
    (791143, 0x6e, 0x70),
    (797143, 0x6d, 0x20),
    (803143, 0x6d, 0x30),
    (809143, 0x6c, 0x50),
)


# The FC8300 datasheet gives UHF 470..806 MHz.  The
# vendor's table runs to 809143, which is 3.1 MHz past that ceiling: the last
# record may be a terminator, and we have never tuned it.  `within_chip` is the
# guard a caller should apply before sending a tune.
CHIP_MIN_KHZ = 470_000
CHIP_MAX_KHZ = 806_000


def within_chip(freq_khz):
    """True when a frequency is inside the FC8300's specified RF range."""
    return CHIP_MIN_KHZ <= freq_khz <= CHIP_MAX_KHZ


def plan(freq_khz):
    """The (byteA, byteB) pair for a frequency, or None (the vendor's rule)."""
    best = None
    for record in RECORDS:
        if abs(record[0] - freq_khz) <= TOLERANCE_KHZ:
            best = record
            break
    return None if best is None else (best[1], best[2])

