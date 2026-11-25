# H9 OPS firmware P2

**P2** is the TD-H9 firmware H9 OPS is built for: TIDRADIO's official
firmware V1.0.38 with additions. The radio works as before; the stock menus,
channels and settings stay. The Radio Info screen shows `V1.0.38.P2`.

| Added | What it does |
|---|---|
| Bluetooth LE KISS TNC | Every APRS packet the radio decodes goes to the phone, and the phone's frames are transmitted on the APRS channel. This is what H9 OPS needs. |
| Radio PIN | The radio only transmits for phones that send the PIN first. |
| Radio info | Battery, channel A and B frequency, channel, power, bandwidth and the APRS switch, for the app's radio card. |
| Transmit timing | The transmitter is held for the packet's real length; longer TX delays key the radio earlier. |
| Bluetooth watchdog | Restarts a stuck Bluetooth link and reports what it found. |
| Scan speed | **Menu → Scan → 7 Scan Speed:** Slow (stock), Normal, Fast. |
| APRS mute on B | APRS packets on channel B stay out of the speaker while they're still decoded, only while APRS is on and listening on B. **Menu → APRS → 8 APRS Mute** turns it off. |
| Battery percent | The status bar shows the charge as `78%`, red at 10% and below. |
| SQ Tail and RX Filter | **Analog Set:** a shorter squelch tail (less crunch at the end of a transmission) and wider receive audio, both off (stock) until you change them. |

## Getting P2

This repository doesn't contain any TIDRADIO firmware. You make P2 yourself
from TIDRADIO's official file, offline, in a minute:

1. **Get the official firmware.** Download the TD-H9 USB-C firmware update
   from TIDRADIO's firmware page (<https://tidradio.com/pages/tidradio-firmware>)
   and unzip it. You need `TD-H9-V1.0.38.fw`
   (SHA-256 `c4d0beb5281f3a06d7a56083e1744b925f78ff9917c8364e006418ff0bbd0cd6`).
2. **Make P2.** Either:
   - **In a browser:** download [`p2-patcher.html`](p2-patcher.html)
     (Raw → save), open it, and choose `TD-H9-V1.0.38.fw`. It saves
     `TD-H9-V1.0.38.P2.fw`. Nothing is uploaded; the page works offline.
   - **With Python 3:** put [`patch_p2.py`](patch_p2.py) and
     [`p2-patch.json`](p2-patch.json) in one folder and run
     `python3 patch_p2.py TD-H9-V1.0.38.fw`.

   Both check the official file before changing anything and check the result
   afterwards (P2's SHA-256 is
   `255c907b5a23228b2b8dffff4ebe0f3dbbea5eee55f772d944b890a7b3e50124`). A
   different or damaged file is refused and nothing is written.
3. **Flash it** (below).

**TIDRADIO's page only offers a newer version, or the patcher won't take your
file?** P2 is made for V1.0.38 only.
[Ask for the firmware](https://github.com/Deadly447/H9-OPS/issues/new?template=firmware-request.yml)
and I'll get it to you directly.

## Flashing

Flashing any firmware is at your own risk. Keep `TD-H9-V1.0.38.fw`: flashing
it again returns the radio to stock (your extra P2 settings are simply
ignored by stock).

The radio is flashed over USB-C from a computer, with the radio in update
mode (radio off, data USB-C cable connected, hold **PTT + 2** while turning it
on: the screen stays blank and the top LED turns green). The
[H9 Toolbox](https://github.com/melidosian/h9toolbox) command-line flasher
(`firmware-research/h9-flasher`) takes stock-derived firmware like P2:

```sh
python3 h9_flasher.py backup TD-H9-V1.0.38.P2.fw --stock-base TD-H9-V1.0.38.fw --device <radio> --backup before-p2.bin
python3 h9_flasher.py flash  TD-H9-V1.0.38.P2.fw --stock-base TD-H9-V1.0.38.fw --device <radio> --backup before-p2.bin --yes
```

Read the flasher's README first (finding `<radio>`, power-cycling between
backup and flash). After flashing, check **Radio Info** shows `V1.0.38.P2`,
then connect H9 OPS.

## Questions and problems

[Open a firmware request](https://github.com/Deadly447/H9-OPS/issues/new?template=firmware-request.yml)
for the firmware itself, or a normal issue for problems with the app.
