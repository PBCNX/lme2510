# lme2510 — LME2510C + FC8300 ISDB-T dongle (`3344:1120`)

User-space driver for the black square **my|phone** dongle sold with **my|digital TV** (`FS550-11A-M`): an **LME2510C**
USB bridge and an **FC8300** tuner, ISDB-T, 6 MHz raster. It speaks the dongle's own USB protocol, uploads its
firmware, tunes, and streams MPEG-TS to a file, stdout or UDP; it reads the multiplex's transmission parameters (TMCC),
the BML data-broadcast documents, captions and the EWBS alert. Python over libusb (pyusb is vendored, so there is
nothing to install); a dependency-free Java core for Android lives in `java`.

**Our own code, no vendor code.**

## Device

| | |
|---|---|
| USB ID | `3344:1120` cold, re-enumerates as `3344:2480` after the firmware is loaded |
| Chip | LME2510C USB bridge with an FC8300 ISDB-T tuner, 6 MHz raster |
| Endpoints | `0x02` OUT commands, `0x01`/`0x81` command and response, `0x88` IN MPEG-TS (alternate setting 1) |
| Status | lock, strength and quality from the demod's own registers |
| TMCC | mode, guard interval, partial reception, layers A/B/C |
| Data broadcast | BML documents from the DSM-CC carousel |
| Captions | ARIB caption management and PES |
| EWBS | activation bit, where the multiplex carries it |

## Platforms

Windows, Linux, macOS and Android. The dongle is measured on Windows and on Android over USB; the Python core on
Windows and the Java core inside a host app.

## Install

From a checkout — pyusb is vendored, so no `pip install` is needed.

```bash
python -m lme2510 --version     # lme2510 0.3.0 (lme2510-pbcnx)
python -m lme2510 list          # finds the 3344 devices present
python -m lme2510 selftest      # the whole path against a simulated device, no hardware needed
```

- **Windows**: bind the dongle to WinUSB with [Zadig](https://zadig.akeo.ie/) and replug it, then download libusb
  from <https://libusb.info> and set `LME2510_LIBUSB` to the full path of `libusb-1.0.dll` (or put that DLL next to
  `python.exe`). Zadig is the device binding; libusb is the library the driver loads, and it is not shipped here.
- **Linux**: `sudo cp udev/99-lme2510.rules /etc/udev/rules.d/ && sudo udevadm control --reload && sudo udevadm trigger`,
  then replug.
- **macOS**: install libusb (`brew install libusb`); on Apple Silicon use an arm64 Python and libusb.

**Firmware.** The vendor images are not in this repository — they are the manufacturer's material. Put
`lme2510-usb-descriptors-500.bin` and `lme2510-code-3138.bin` in `firmware/` beside the package; they are checked
before use. `--version`, `list` and `selftest` work without them; anything that opens the stick needs them.

## Use

```bash
python -m lme2510 list
python -m lme2510 tune   --freq 497143
python -m lme2510 tmcc   --freq 497143
python -m lme2510 play   --freq 497143 --seconds 30 --out capture.ts
```

Android / JVM:

```java
Tuner tuner = provider.open(context, device, firmware, listener);
tuner.open();
tuner.tune(497143);
```

The Android core lives in `java`; the activity that opens it belongs to the host app.

## License

MIT — see `LICENSE`. `NOTICE` lists what is not ours.

MIT. Copyright (c) 2026 The LME2510 Open Driver Authors. Marker: lme2510-pbcnx.
