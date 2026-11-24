# How to use the LME2510 driver

This guide is for anyone who has the dongle and wants to watch or record TV with it. You do not need to be a
programmer. Follow the numbered steps in order.

---

## 1. What you need

1. The dongle itself: the black square **my|phone** stick sold with the **my|digital TV** app. It contains the
   **LME2510C** bridge and the **FC8300** tuner. Its USB ID is `3344:1120`.
2. A computer running Windows, Linux or macOS with **Python 3.9 or newer**. Typing `python --version` should show that
   number, or higher.
3. The **two firmware files** for this stick. The stick is blank when it is first plugged in, and these files are what
   bring it to life. Section 2 tells you exactly where to put them.
4. The driver itself: this repository, downloaded as a ZIP or cloned with git.

You do **not** need to run `pip install`. Everything the driver needs to talk to the stick is already in the folder.

---

## 2. Where to put the firmware files

These files are the manufacturer's, and this repository never ships them. You get them from vendor software you already
have (the app that came with the stick): find its firmware files, copy them somewhere you will remember, and put them in
the driver's own folder:

1. Create a folder named `firmware` inside the repository, beside the `lme2510` folder.
2. Put the two files in it, with exactly these names:

   ```text
   firmware/lme2510-usb-descriptors-500.bin      500 bytes
   firmware/lme2510-code-3138.bin                3,138 bytes
   ```

3. Nothing else needs to be set. Every command finds them there.

The descriptor blob must be **500 bytes** and the code image **3,138 bytes**. `python -m lme2510 doctor` prints
both files' sizes and SHA-256 fingerprints, so you can compare them with what you were given. A file of the wrong
size, or a name the driver does not know, is refused before anything is written to the stick:

```text
unknown firmware file: firmware/lme2510-code.bin
```

**On Android** there is no folder: the app supplies the images itself. It asks the user for them once, keeps them, and
passes them to the driver when it opens the stick.

**You can do a lot without these files.** The three commands in section 4 work without them — `selftest` needs no
hardware at all. Tuning and playing need them, because the stick cannot receive until it has been loaded.

---

## 3. Install

Open a terminal in the folder you downloaded, and stay in that folder for every command in this guide.

### Windows

Windows needs two different things, and one is not the other: **Zadig** tells Windows which driver to use for the
stick, and **libusb** is the library the driver itself loads. Do both.

1. Plug in the stick.
2. Download **Zadig** from <https://zadig.akeo.ie/> and run it.
3. In Zadig, open the drop-down list and choose **Show All Devices**, then pick the one whose USB ID is `3344:1120`.
4. Set the driver on the right to **WinUSB** and click **Install Driver**. Wait for "The driver was installed
   successfully".
5. Unplug the stick and plug it in again. You do this only once.
6. Get **libusb** from <https://libusb.info> (Downloads → Windows) and unzip it. You need `libusb-1.0.dll` from the
   64-bit folder. This file is not part of the repository.
7. Put that file where the driver looks for it. The simplest way is to point the driver at it:

   ```powershell
   setx LME2510_LIBUSB "C:\path\to\libusb-1.0.dll"
   ```

   (Copying `libusb-1.0.dll` next to `python.exe` works too, and needs no variable.) Close the terminal and open a new
   one, because `setx` only affects terminals opened afterwards.
8. Run `python -m lme2510 list` (section 4). You should see the stick, or one line saying no device was found if it is
   not plugged in. Either answer means libusb was found; the error in section 8 means it was not.

### Linux

1. Copy the rule that lets your user reach the stick without being root:

   ```bash
   sudo cp udev/99-lme2510.rules /etc/udev/rules.d/
   sudo udevadm control --reload
   sudo udevadm trigger
   ```

2. Unplug the stick and plug it in again.
3. If you are connected over SSH (no desktop session logged in), add your user to the group the rule grants, then log
   out and back in.

### macOS

1. Install libusb with Homebrew: `brew install libusb`.
2. On an Apple Silicon Mac, use an arm64 build of Python. A Python running under Rosetta will report that the USB
   backend is missing.

---

## 4. First run

Three commands tell you whether everything is in place. Run them in this order.

1. **Check the version.**

   ```bash
   python -m lme2510 --version
   ```

   A good answer is one line:

   ```text
   lme2510 0.3.0 (lme2510-pbcnx)
   ```

2. **Find the stick.**

   ```bash
   python -m lme2510 list
   ```

   A good answer names the stick, for example:

   ```text
   3344:1120  bus 1 addr 41  LME2510 (cold)
   ```

   If it says `no LME2510 device on the bus`, the stick is not plugged in, or Windows has not been told to use WinUSB
   for it (section 3). That line exits with code 1, which is normal for "not found".

3. **Try it with no hardware at all.** This runs the whole sequence against a simulated stick, so it works even with
   nothing plugged in:

   ```bash
   python -m lme2510 selftest
   ```

   A good answer ends with `OK`:

   ```text
   selftest  init    330 frames, sweep peak 0x16
   selftest  tune    243 writes in total, 0x76 = 0x20
   selftest  scan    LOCKED (8 reads)
   selftest  stream  030600ff011f2081 + 0600
   selftest  OK
   ```

   If `selftest` works but `list` does not, the problem is on the USB side, not in the driver: go back to section 3.

---

## 5. Watch or record

The stick tunes to one channel at a time. A frequency is given in kHz, so `497143` means 497.143 MHz.

1. **Tune and see if there is a signal.**

   ```bash
   python -m lme2510 tune --freq 497143
   ```

   A good answer says the channel locked and names the registers it checked. `NO LOCK` means the channel is empty or
   the signal is too weak where you are. Try another frequency, move the antenna, or use
   `python -m lme2510 scan --start 470000 --stop 620000` to look across the band.

2. **Record 30 seconds to a file.**

   ```bash
   python -m lme2510 play --freq 497143 --seconds 30 --out capture.ts
   ```

   You get `capture.ts` in the current folder. Any player that reads MPEG-TS can open it. Press `Ctrl-C` to stop early.

3. **Send it to a player instead of a file.** The stick's stream can go over UDP, which is how a TV front end opens it:

   ```bash
   python -m lme2510 play --freq 497143 --udp 127.0.0.1:5000
   ```

---

## 6. After the first run

1. **A channel that locks but delivers nothing.** Some channels are almost entirely padding. Ask for the padding to be
   kept, and the stream usually appears:

   ```bash
   python -m lme2510 play --freq 485143 --keep-null --seconds 10 --out idle.ts
   ```

2. **Read the channel's transmission parameters.**

   ```bash
   python -m lme2510 tmcc --freq 497143
   ```

3. **Check the whole stack when something is wrong.**

   ```bash
   python -m lme2510 doctor
   ```

   It reports the driver version, the Python stack, libusb, the udev rule and whether the device is on the bus, one
   line each, and exits 1 if any of them is missing.

---

## 7. If there is no picture

1. **No stick found.** Windows: bind it with Zadig again, and confirm `LME2510_LIBUSB` points at
   `C:\path\to\libusb-1.0.dll`. Linux: check the udev rule was installed and replug.
2. **Tune says NO LOCK.** The channel is empty, weak, or the antenna is in a bad place. Move the antenna first: this
   tuner is sensitive, and a weak position starves the stream in short patches rather than corrupting it, which looks
   like a stall in a player.
3. **The stream stops after a few minutes.** Unplug the stick, wait five seconds, plug it in again, and run the command
   again. The driver reopens a stick that resets, but a cable that is physically loose will always win.
4. **A channel that worked yesterday delivers nothing.** Run `python -m lme2510 scan --start 470000 --stop 620000`.
   Broadcasters move multiplexes.

---

## 8. Errors and what they mean

The driver prints one line and exits, and never shows a Python traceback for the four ordinary failures:

```text
libusb is not available: ...      libusb is missing: install it from libusb.info and set LME2510_LIBUSB (section 3)
no LME2510 device on the bus (3344:1120 or 3344:2480)   nothing plugged in, or Windows is not using WinUSB (exit code 1)
cannot claim the device (busy): ...  another program owns the stick: close it, or unplug and replug
unknown firmware file: <name>       a file is missing from firmware/, or has the wrong name (section 2)
```

A stream that stalls exits with code 4 and says so on one line. Replug the stick and run the command again.

---

## 9. Where things are

```text
lme2510/                 the Python driver
java/                    the Java core for Android
udev/99-lme2510.rules    the Linux rule
firmware/                where you put the vendor images (not shipped)
LICENSE · NOTICE         the license, and what is not ours
```

MIT. Copyright (c) 2026 The LME2510 Open Driver Authors. Marker: lme2510-pbcnx.
