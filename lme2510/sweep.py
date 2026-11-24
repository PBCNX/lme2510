"""The `0f1c` signal sweep, transcribed: `FUN_00019dbc` (0x9dbc).

Tuner Init calls it once, after the default tune, and stores the result as
`peak - 7` for the `0x1c` write a later tune computes (`lme2510/tune.py`).

  for step in 0x13 .. 0x1b:               (nine steps)
      0x1c <- step
      read 0x0ff5 three times, level = sum // 3
      keep the step with the highest level

It returns the best step and leaves `0x1c` at the last one (0x1b) — the chosen
value is *not* written back here, it arrives later as `clamp(table + peak - 7)`.
"""
from lme2510 import replay

TUNER = 0xB0
PAGE = 0x0F00

REGISTER = 0x1C
MEASURE = 0x0FF5
STEPS = tuple(range(0x13, 0x1C))     # 0x13 .. 0x1b
READS_PER_STEP = 3


def write(register, value):
    return replay.write_frame(PAGE | register, value, device=TUNER)


def run(send, read, wait=None):
    """Run the sweep; returns ``(peak_step, levels)``."""
    wait = wait or (lambda: None)
    levels = []
    best_step, best_level = None, -1
    for step in STEPS:
        send(write(REGISTER, step))
        total = 0
        for _ in range(READS_PER_STEP):
            data = read(MEASURE, 1)
            total += data[0] if data else 0
        level = total // READS_PER_STEP
        levels.append(level)
        if level > best_level:
            best_step, best_level = step, level
    return best_step, levels

