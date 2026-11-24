# Changelog

## 0.3.0 — 2026-11-24

- Package renamed to `com.pbcnx.drivers.lme2510`; the Android door contract is `com.pbcnx.tuner` 2.0.0.
- `java`: a dependency-free Java core and the contract; any Android activity can open it.
- Android: one transport per tuner, a queued reader with four transfers in flight, and recovery that reopens a stick
  which resets and comes back as the cold device.
- Reader: the driver counts its own completions, dropped chunks and TS packets, and logs them every 10 s.
- Streaming: the reader hands the host the full rate the multiplex carries (measured 575 KiB/s at 11.5 completions/s,
  0 dropped, gaps under 200 ms).
- TMCC: mode, guard interval, partial reception and the three layers are decoded, and the record is captured while the
  pump is not armed.
- Data broadcast, captions and EWBS: BML documents from the carousel, ARIB captions, and the EWBS activation bit.
- CLI: a machine without libusb, without a stick, or with the stick held by another program now gets one line and a
  non-zero exit, not a traceback.
- The vendor firmware is never distributed in this repository; put it in `firmware/` and the driver checks it before use.
- License: MIT for our code; `NOTICE` lists what is not ours.

## 0.2.0 — 2026-11-21

- Cold bring-up measured end to end: the 500-byte descriptor blob, the 3,138-byte code image, and the warm init.
- Tuning, the lock scan and the filter, with the stream as the only verdict.
- The Python core talks to the stick on Windows and Linux; the Java core follows the same transport shape.

## 0.1.1, 0.1.0 — 2026-11

- First working protocol: firmware upload, tune, lock, MPEG-TS read.

MIT. Copyright (c) 2026 The LME2510 Open Driver Authors. Marker: lme2510-pbcnx.
