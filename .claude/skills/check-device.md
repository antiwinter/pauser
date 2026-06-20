---
name: check-device
description: Check HDR display capability and Dolby Vision / video codec support on a connected Android device via adb
---

## Usage

```
/check-device [serial]
```

`serial` is the adb device serial (e.g. `192.168.17.56:5555` or `25b41579`). If omitted, uses the only connected device.

---

## Steps

Run the following two checks in parallel (no dependencies between them).

### 1. Display HDR capability

```sh
adb [-s <serial>] shell dumpsys display 2>&1 | grep -i "hdrCapabilities\|supportedHdrTypes"
```

From the output, find `mSupportedHdrTypes=[...]`. The type IDs map as:
- `1` → Dolby Vision
- `2` → HDR10
- `3` → HLG
- `4` → HDR10+

An empty list `[]` means the display reports no HDR support and `display.isHdr()` will return `false` in app code.

### 2. Video codec support

**Step 1 — find codec XML files:**
```sh
adb [-s <serial>] shell "find /vendor /system -name 'media_codecs*.xml' 2>/dev/null"
```

**Step 2 — search for video codec entries across all found XMLs:**
```sh
adb [-s <serial>] shell "grep -rh 'MediaCodec.*type=\"video/' /vendor/etc/ /vendor/tvconfig/ /system/apex/ 2>/dev/null | sort -u"
```

**Step 3 — search specifically for Dolby Vision:**
```sh
adb [-s <serial>] shell "grep -ri 'dolby.vision\|dolby-vision\|DOLBY_VISION\|dovi' /vendor/etc/ /vendor/tvconfig/ 2>/dev/null | grep -i 'codec\|MediaCodec\|video' | head -20"
```

Also check for Dolby Vision hardware files as a secondary signal:
```sh
adb [-s <serial>] shell "find /vendor /system -name '*dolby*' -o -name '*dovi*' 2>/dev/null | grep -v '.apk\|.jar\|audio\|dap\|tuning' | head -10"
```

### Interpreting results

| Finding | Conclusion |
|---|---|
| `mSupportedHdrTypes=[1,2,3,4]` | Display supports DV, HDR10, HLG, HDR10+ |
| `mSupportedHdrTypes=[]` | Display reports no HDR — app's `isDisplayHdrCapable()` returns false |
| `OMX.MS.DOLBY_VISION.*` or `c2.*.dolby.vision.*` entries | Hardware DV decoder present |
| No `video/dolby-vision` in any XML | No DV video decoder — DV content will fail or fall back to base layer |
| Dolby audio files only (`dap`, `eac3`, `ac4`) | Audio Dolby only, no DV video |

### Summary to report

After running the checks, report:

- **Display**: HDR types supported (list names, not numbers), or "no HDR"
- **Dolby Vision video**: present/absent, and which profiles if present (DVHE, DVAV1, DVAV, AVC-DV, etc.)
- **Other notable video codecs**: HEVC, AV1, VP9, AVC, etc.
- **Verdict**: suitable for HDR/DV testing or not
