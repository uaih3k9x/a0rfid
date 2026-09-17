# A0 protocol — R2000-family desktop UHF reader

Serial: **115200 8N1** over CH340 USB-UART. Default reader address `0x00`.

## Frame

```
A0 | LEN | ADDR | CMD | DATA... | SUM
```

- `LEN` = 3 + len(DATA) — counts itself, ADDR, CMD, DATA. Not the header, not the checksum.
- `SUM` = two's complement of the 8-bit sum of all preceding bytes: `(-sum) & 0xFF`
- Responses echo CMD as-is (no 0x80 flag on tested firmware; match both to be safe)
- Frame total length = LEN + 2

## Commands that matter

| cmd | name | request DATA |
|---|---|---|
| 0x72 | get firmware version | — |
| 0x74 / 0x75 | set / get work antenna | `[ant]` / — |
| 0x76 / 0x77 | set / get output power | `[dBm]` / — |
| 0x79 | get frequency region | — |
| 0x81 | **read tag** | `{memBank, wordAddr×4, wordCnt×2, pwd×4}` LEN=0x0E |
| 0x82 | **write tag** | `{pwd×4, memBank, wordAddr×4, wordCnt×2, data...}` LEN=2·cnt+0x0E |
| 0x83 / 0x84 | lock / kill | `{pwd×4, mb, lockType}` / `{pwd×4}` |
| 0x89 | realtime inventory | `[ant]` — streams tag frames until stopped |
| 0x8C | **stop inventory** | — |

memBank: `0`=RESERVED `1`=EPC `2`=TID `3`=USER. Addresses/lengths in **words (2B)**.

> READ and WRITE field order differs — pwd sits last in READ, first in WRITE.
> This asymmetry comes straight from the vendor's native encoder. It is not a typo.

## Responses

Config queries: DATA is the value, no status byte.

Tag ops: first DATA byte is status — `0x00` success, `0x11` fail/no tag, `0x43` out of bank range.
A malformed or tagless READ gets **total silence**, not an error frame.

Realtime inventory tag frame DATA:

```
[antenna][PC×2][EPC...][RSSI×4][freq×3(BE, kHz)]
```

READ data frame DATA:

```
[status 0x00][ant][rssi][PC×2][EPC...][CRC×2][readData×N][N×2(BE)][0x01 0x01]
```

locate the payload via the trailing BE length field, not by counting headers.
EPC length in bytes is `((PC >> 11) & 0x1F) * 2`. Validate it against the
response layout and compare the returned EPC with the expected tag.

## EPC bank layout (Gen2)

```
word 0: CRC16   word 1: PC   word 2...: EPC
```

EPC data starts at word 2; the chip maintains CRC. When changing EPC length,
update bits 15–11 of PC with the new word count and preserve the other PC bits.
The Android writer sends PC and EPC together starting at word 1 and checks both
by reading them back.
