# lme2510 — LME2510C + FC8300 ISDB-T dongle

User-space driver for the dongle sold with **my|digital TV**: a black square
`my|phone` unit, model **FS550-11A-M**, with an **LME2510C** USB bridge and an
**FC8300** tuner — USB **3344:1120** (also listed: 2480, 1111).

**Status: in development, no hardware.** The vendor protocol has been recovered
from the app under emulation — the init sequence, the 8051 firmware upload and
the frame format are captured and replayable — and the cross-platform skeleton
runs on Windows, macOS, Linux and Android. The one missing piece is the stick
itself: the tuner path cannot be finished without one.

## The stick

If you have a spare **my|phone FS550-11A-M / LME2510C** dongle and would lend or
donate it, please [open an issue](https://github.com/PBCNX/lme2510/issues/new?template=stick-donation.md).
A single unit is enough. It will be treated as the single unit it is: read-only
until the transport is proven, nothing soldered, and photographed before any
physical step.

## What this repo is

| | |
|---|---|
| Device | LME2510C bridge + FC8300 tuner, ISDB-T, USB `3344:1120` |
| Host stack | libusb (Windows/macOS/Linux), `UsbManager` (Android) |
| Firmware | 3,138 B of 8051 code + a 500 B descriptor blob, uploaded at init |
| Licence | MIT — no vendor code, no vendor binaries, no firmware in this tree |

The working tree during development is private; this public repository will get
a curated source snapshot once the device tunes and hands back TS, with the
vendor material withheld and verified.

## Licence

MIT. Third-party and provenance details travel with the source snapshot.
