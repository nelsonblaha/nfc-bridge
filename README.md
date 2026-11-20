# NFC Bridge

Use an Android phone's NFC reader from a computer, over `adb`.

Open the app, hold a card to the back of the phone, and drive it from the
computer: read it, write NDEF records (URLs, text, Home Assistant tags), or send
raw commands to the chip. The app can also show an instruction on the phone's
screen ("hold card 3 now"), which helps when one person holds cards and a script
or an assistant on the computer does the programming.

It is a small, dependency-free debug app (two Java files, built with the bare
Android SDK tools, no Gradle) plus a Python client.

## How it works

```
computer                         phone (app open, screen unlocked)
nfcb.py ── adb forward ──► 127.0.0.1:47800  NFC Bridge ── reader mode ──► card
```

- While the app is on screen, Android delivers every tag to it (reader mode),
  instead of the normal tag dispatch.
- The app serves newline-delimited JSON on **127.0.0.1:47800 only**. The
  computer reaches it with `adb forward tcp:47800 tcp:47800`.
- **Authentication:** each launch writes a random 128-bit token to the app's
  private files. The client reads it with `adb shell run-as`, which works only
  because this is a debug build and only for the adb shell user, so other apps
  on the phone cannot read it. Any request before a valid `auth` closes the
  connection.

## Build and install

Needs an Android SDK with `build-tools/35.0.0` and `platforms/android-35`, and a
JDK 11+. Set `ANDROID_HOME` if the SDK is not in `~/Android/Sdk`.

```sh
./build.sh                                   # -> build/nfc-bridge.apk (debug-signed)
adb install -r build/nfc-bridge.apk
./nfcb.py launch
```

Works over USB or wireless debugging. With several devices attached, pass
`-s SERIAL` or set `NFCB_SERIAL`. `NFCB_ADB` and `NFCB_ADB_PORT` select the adb
binary and a non-default adb server port.

## Client

```sh
./nfcb.py status
./nfcb.py show "Hold the blue card to the back of the phone"
./nfcb.py wait --timeout 60          # next tag that appears (--current: accept one already there)
./nfcb.py info                       # UID, technologies, ATQA/SAK, NDEF capacity, cached records
./nfcb.py read                       # NDEF records, decoded where possible
./nfcb.py write-uri https://example.com
./nfcb.py write-text "hello" --lang en
./nfcb.py write-ha-tag [TAG_ID]      # Home Assistant tag; random UUID if omitted
./nfcb.py raw NfcA 3004              # READ pages 4-7 of an NTAG / Ultralight
./nfcb.py mfc-read 4 --key FFFFFFFFFFFF
./nfcb.py json '{"cmd":"transceive","tech":"IsoDep","hex":"00A4040007D2760000850101"}'
```

Each reply is printed as JSON; the exit status is 1 when the bridge answers
`"ok": false`. Keep the card still on the phone until a write returns. If it
moves, the reply is `tag_lost` and you tap again.

## Protocol

One JSON object per line in each direction. Requests carry an optional `id`,
which the reply echoes.

| `cmd` | arguments | reply |
|---|---|---|
| `auth` | `token` | `version` |
| `ping`, `status` | | NFC on/off, current tag, open connection |
| `show` | `text` | shows the text on the phone and vibrates |
| `wait_tag` | `timeout_ms`, `after_seq`, `accept_current` | `tag` (as `info`) |
| `info` | | `tag`: `uid`, `techs`, `nfca`, `ndef`, `isodep`, … |
| `read_ndef` | | `records`, `bytes`, `max_size` |
| `write_ndef` | `records`, `format` (default true) | `bytes`, `method` |
| `transceive` | `tech`, `hex`, `timeout_ms`, guard flags | `hex` |
| `mfc_read` / `mfc_write` | `block`, `key_hex`, `key_type`, `hex` | `hex` / `written` |
| `make_readonly` | `confirm: "PERMANENT"` | `done` |
| `close` | | closes the open technology connection |

NDEF record kinds for `write_ndef`: `uri`, `text` (`lang`), `mime` (`type`),
`external` (`domain`, `type`), `aar` (package name), and `raw` (`tnf`,
`type_hex`, `id_hex`, `payload_hex`). `mime` and `external` take `value` (UTF-8)
or `payload_hex`.

`tech` is one of `NfcA`, `NfcB`, `NfcF`, `NfcV`, `IsoDep`, `MifareUltralight`,
`MifareClassic`. Android exposes MIFARE Classic only on phones with an NXP NFC
controller.

## Guards against permanent damage

Some writes can never be undone. The bridge refuses them unless the request
opts in explicitly:

- **NTAG21x / MIFARE Ultralight**: raw `WRITE` / `COMPATIBILITY WRITE` to
  page 2 (static lock bytes) or page 3 (one-time-programmable capability
  container) needs `allow_lock`. For chips identified with `GET_VERSION`
  (NTAG213/215/216, Ultralight EV1), the dynamic lock page needs `allow_lock`
  and the configuration pages (AUTH0, ACCESS, PWD, PACK) need `allow_config`.
  An unidentified chip gets a `warning` that only pages 2–3 are guarded.
- **MIFARE Classic**: writing a sector trailer (keys and access bits) needs
  `allow_trailer`; block 0 needs `allow_block0`.
- **NDEF**: `make_readonly` needs `confirm: "PERMANENT"`.

These guards are a safety net, not a complete model of every chip. Read your
tag's datasheet before sending raw writes.

## Limits

- The app must be open on screen with the phone unlocked; Android stops NFC
  when the screen is off or another app is in front.
- Low-frequency 125 kHz cards (many older building and prox badges) are not
  NFC and cannot be read by a phone at all.
- Tested so far on one phone (Pixel, Android 17): NTAG/Ultralight reads and
  NDEF writes. Other technologies are implemented but less exercised.
- Debug build by design: `run-as` is how the token reaches the computer.
