# a0rfid

your desktop uhf reader speaks A0. now you do too.

915MHz USB RFID readers that ship as "windows software + apk" in a zip.
no vendor app. no kernel driver. no windows.

## what

| file | is |
|---|---|
| `rfid_tool.py` | cli — info / inventory / read / write / scan |
| `rfid_gui.py` | gui. one button does everything |
| `ch340_usb.py` | userspace ch340 driver (pyusb) |
| `android/` | landscape app. auto-read, selective writing, readback checks, clipboard import |
| `android/test.sh` | offline Android protocol, memory, writing and clipboard tests |
| `test_offline.py` | all of it. no hardware needed |

## run

```
pip3 install pyusb pyserial
python3 test_offline.py                        # no reader? still green
python3 rfid_tool.py --port USB info           # yes, "USB". userspace.
python3 rfid_tool.py --port USB inventory
python3 rfid_tool.py --port USB read --bank TID --addr 0 --len 6
```

linux with the stock ch341 kernel driver: `--port /dev/ttyUSB0`.
mac: prefix `DYLD_LIBRARY_PATH=/opt/homebrew/lib` (homebrew libusb).

## android

[Download the APK](android/RFID助手.apk). Requires Android 7.0+ and USB Host.
Connect the reader, grant USB access, and place one tag on it. The app stops
inventory at the first tag, reads its memory banks, and keeps the result on screen.

Quick write lets you select TID, EPC, USER and RESERVED, edit their data, and run
either a comparison or a write followed by readback. Copy a read result and use
the clipboard import button to fill the form. Most ordinary tags have read-only TID.

[Android usage and build instructions / 安卓使用说明](docs/ANDROID.md).

```
bash android/test.sh
```

## protocol

```
A0 LEN ADDR CMD DATA... SUM      — every direction, every time
```

READ and WRITE rearrange their fields differently. the java sdk won't tell you.
[docs/PROTOCOL.md](docs/PROTOCOL.md) will.

## why

bought a reader. got a zip full of exe.
→ [docs/REVERSE.md](docs/REVERSE.md)

MIT.
