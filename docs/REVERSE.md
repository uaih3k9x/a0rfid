# how the frames were found

1. the pdf manual says: install the ch341 driver, pick a com port, 115200, click connect.
2. the android apk decompiles clean. the java sdk shows READ as
   `{mb, 0, cnt, addr, pwd}`. **it is a lie.**
3. the app never calls that java codec. it calls a jni wrapper into `libnative-lib.so`.
4. in the .so, `rfid_encode()` is a 149-case jump table. every command gets its own
   data-area rearrangement. READ hits case 129: `{mb, addr×4, cnt×2, pwd×4}`.
   WRITE hits case 130: pwd first. stop-inventory is 0x8C, not 0x8B.
5. sent the reordered frames to real hardware. tags answered.
   changed a byte, read it back, restored it. write path confirmed.

## scars

- a jni layer means the java `byte[]` is an api struct, not a wire format.
- the reader never acks a malformed or tagless READ. silence is not an error
  code, it is rejection. this costs an evening if you learn it the hard way.
- status `0x00` means success on tag ops. `0x10` is only a command-layer ack.
- the firmware hangs sometimes. power cycle and move on.
- max output power on this unit: 20 dBm.

no vendor code is included in this repo. only frames observed on the wire
and code written from those observations.
