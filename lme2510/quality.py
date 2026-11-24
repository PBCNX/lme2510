"""Stream quality by the studio's shared definition (one rule, every driver line).

    quality % over a sliding window (default 5 s) = 100 * clean packets / total

A packet is **not clean** when its transport_error_indicator is set, or when it breaks
the continuity counter of its PID. A repeated counter (a duplicate packet) is not a
break. Null packets (`0x1FFF`) and adaptation-field-only packets are not evaluable and
are skipped by both the numerator and the denominator.

This is a property of the stream we **received** — it cannot tell a weak antenna from a
lossy USB path by itself (TEI does hint at the former). That is why it is reported next
to `strength`, which is the tuner's own level: the pair says "how strong" and "how
intact", and neither pretends to be a C/N measurement.

Unknown until the first full window has been observed: a percentage from 0.3 s of
stream is noise, and an early wrong number is worse than a dash.
"""
import time

#: The window the studio's definition uses, in seconds.
WINDOW = 5.0


class Quality:
    """Cumulative packet counts plus a sliding window over them."""

    def __init__(self, window=WINDOW):
        self.window = window
        self.total = 0          # evaluable packets seen
        self.unclean = 0        # of those, TEI set or continuity broken
        self.samples = []       # (time, total, unclean) at each feed
        self.started = None

    # -- counting ---------------------------------------------------------
    def observe(self, tei, breaks_continuity):
        """One evaluable packet: was it damaged, and did it break the counter?"""
        self.total += 1
        if tei or breaks_continuity:
            self.unclean += 1

    # -- the window -------------------------------------------------------
    def mark(self, now=None):
        """Record the cumulative counts; call once per chunk."""
        now = time.monotonic() if now is None else now
        if self.started is None:
            self.started = now
        self.samples.append((now, self.total, self.unclean))
        # keep one sample at or before the window edge as the baseline
        while len(self.samples) > 2 and now - self.samples[1][0] >= self.window:
            self.samples.pop(0)

    def percent(self, now=None):
        """Quality over the window, or ``None`` until a full window has been seen."""
        if self.started is None or not self.samples:
            return None
        now = time.monotonic() if now is None else now
        if now - self.started < self.window:
            return None
        base_time, base_total, base_unclean = self.samples[0]
        total = self.total - base_total
        unclean = self.unclean - base_unclean
        if total <= 0:
            return None
        return 100.0 * (total - unclean) / total

    def token(self, now=None):
        """What a status line carries: ``"99.8"``, or ``"n/a"`` while unknown."""
        value = self.percent(now)
        return "n/a" if value is None else "%.1f" % value
