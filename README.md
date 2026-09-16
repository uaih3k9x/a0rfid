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
| `android/` | 24kb apk. sideload, plug, scan |
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
