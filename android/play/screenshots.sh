#!/usr/bin/env bash
# Takes the Play Store phone screenshots on a running emulator (see
# .github/workflows/screenshots.yml). Usage: screenshots.sh path/to/app.apk out_dir
set -euo pipefail
APK=$1
OUT=$2
PKG=app.sunnyside.news
mkdir -p "$OUT"

shot() { sleep "${2:-4}"; adb exec-out screencap -p > "$OUT/$1.png"; echo "Saved $1.png"; }

# Tap the centre of the first on-screen element whose text matches a regex.
tap() {
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null
  adb pull /sdcard/ui.xml "$RUNNER_TEMP/ui.xml" > /dev/null
  read -r x y < <(python3 - "$RUNNER_TEMP/ui.xml" "$1" "${2:-0}" <<'PY'
import re, sys, xml.etree.ElementTree as ET
path, pattern, min_len = sys.argv[1], re.compile(sys.argv[2]), int(sys.argv[3])
for n in ET.parse(path).iter("node"):
    text = n.get("text") or n.get("content-desc") or ""
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
    if pattern.search(text) and len(text) >= min_len and y1 > 250:
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
else:
    sys.exit(f"Nothing on screen matches {pattern.pattern}")
PY
)
  adb shell input tap "$x" "$y"
}

# A tidy status bar: full battery and signal, 08:00, no notification icons.
adb shell settings put global sysui_demo_allowed 1
demo() { adb shell am broadcast -a com.android.systemui.demo -e command "$@" > /dev/null; }
demo enter
demo clock -e hhmm 0800
demo battery -e level 100 -e plugged false
demo network -e wifi show -e level 4 -e mobile show -e datatype none -e level 4
demo notifications -e visible false

adb install -r -g "$APK"   # -g grants the notification permission, so no prompt on first launch
adb shell cmd uimode night no

adb shell am start -W -n "$PKG/.MainActivity" > /dev/null
shot 1-feed 25                          # time for the feed and its pictures to load
adb shell input swipe 540 1500 540 500 600
shot 2-feed-more 8
tap '.' 45 || true                             # the first long headline on screen: open that story
shot 3-story 10

adb shell input keyevent KEYCODE_BACK
adb shell cmd uimode night yes
adb shell am force-stop "$PKG"
adb shell am start -W -n "$PKG/.MainActivity" > /dev/null
shot 4-feed-dark 20
tap '^Settings$' || true
shot 5-settings-dark 4
adb shell cmd uimode night no
