#!/usr/bin/env bash
# McCal's Fold from the Mac, without guessing: finds it, installs Keyd Dev on it, and looks at it.
#
#   scripts/phone.sh connect             USB first, then wireless debugging, then the last address that worked
#   scripts/phone.sh install <apk>       install over the copy there, keep its data, check the version and keyboard
#   scripts/phone.sh screen              which screen is on (cover or inner)
#   scripts/phone.sh shot [name]         a screenshot of the screen that is on, printed as a path
#   scripts/phone.sh tap <x> <y>         a tap on the screen that is on
#
# USB is tried first because it always works. Wireless debugging turns itself off, changes port every time it comes
# back, and only works while the Mac and the phone are on the same network; when it can't, this says which of those
# it is instead of failing quietly. The last wireless address is kept in ~/.config/keyd/phone.
set -euo pipefail

serial=${KEYD_PHONE_SERIAL:-RFGL74FJRJK}          # the Fold8's own serial, as adb names it over USB and in mDNS
state="$HOME/.config/keyd/phone"
shots="${KEYD_SHOTS:-${TMPDIR:-/tmp}/keyd-shots}"

die() { echo "phone: $*" >&2; exit 1; }
command -v adb >/dev/null || die "adb is not on PATH (it comes with the Android SDK platform-tools)."

# The device adb should talk to, printed as its adb serial.
connect() {
    local usb wifi addr
    usb=$(adb devices | awk -v s="$serial" '$1 == s && $2 == "device" { print $1 }')
    if [[ -n "$usb" ]]; then echo "$usb"; return; fi
    wifi=$(adb devices | awk '$1 ~ /:[0-9]+$/ && $2 == "device" { print $1; exit }')
    if [[ -n "$wifi" ]]; then echo "$wifi"; return; fi
    for _ in 1 2 3 4 5; do
        addr=$(adb mdns services 2>/dev/null | awk -v s="$serial" '$1 ~ s && $2 ~ /tls-connect/ { print $3; exit }')
        [[ -n "$addr" ]] && break
        sleep 1
    done
    [[ -z "$addr" && -f "$state" ]] && addr=$(cat "$state")
    if [[ -n "$addr" ]] && adb connect "$addr" 2>/dev/null | grep -q "connected to"; then
        mkdir -p "$(dirname "$state")" && echo "$addr" > "$state"
        echo "$addr"; return
    fi
    # Say why. A different network is the usual reason, and the one nothing else would tell you.
    local mac phone
    mac=$(ipconfig getifaddr en0 2>/dev/null || true)
    phone=${addr%%:*}
    if [[ -n "$phone" && -n "$mac" && "${phone%.*}" != "${mac%.*}" ]]; then
        die "the phone was last at $phone, but this Mac is on ${mac%.*}.x. Plug in USB, or join the same Wi-Fi."
    fi
    die "no phone. Plug in USB, or turn on Settings > Developer options > Wireless debugging and keep that screen open."
}

# The screen that is on: its uniqueId number, which is what screencap -d takes, and whether it is the cover.
screen() {
    local dev=$1
    adb -s "$dev" shell dumpsys display | awk '
        /DisplayDeviceInfo\{/ && /state ON/ {
            match($0, /uniqueId="local:[0-9]+"/); id = substr($0, RSTART + 16, RLENGTH - 17)
            w = ($0 ~ /1248 x 1972/) ? "cover" : "inner"
            print id, w; exit
        }'
}

cmd=${1:-connect}; shift || true
case "$cmd" in
    connect)
        dev=$(connect); echo "connected: $dev"
        ;;
    install)
        apk=${1:-}; [[ -f "$apk" ]] || die "install needs an APK path."
        dev=$(connect)
        sdk=${ANDROID_HOME:-$HOME/Library/Android/sdk}
        aapt=$(ls -d "$sdk"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -1 || true)
        pkg=$("$aapt" dump badging "$apk" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")
        want=$("$aapt" dump badging "$apk" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p")
        keyboard=$(adb -s "$dev" shell settings get secure default_input_method | tr -d '\r')
        adb -s "$dev" install -r "$apk" >/dev/null
        got=$(adb -s "$dev" shell dumpsys package "$pkg" | sed -n 's/.*versionName=//p' | head -1 | tr -d '\r')
        [[ "$got" == "$want" ]] || die "installed, but $pkg reports $got, not $want."
        # An update can drop the keyboard back to Android's default; put back whatever was chosen before.
        now=$(adb -s "$dev" shell settings get secure default_input_method | tr -d '\r')
        if [[ "$now" != "$keyboard" ]]; then
            adb -s "$dev" shell ime set "$keyboard" >/dev/null && echo "put the keyboard back to $keyboard"
        fi
        echo "installed $pkg $got on $dev; keyboard is $(adb -s "$dev" shell settings get secure default_input_method | tr -d '\r')"
        ;;
    screen)
        dev=$(connect); screen "$dev"
        ;;
    shot)
        dev=$(connect); read -r id which < <(screen "$dev")
        [[ -n "${id:-}" ]] || die "no screen is on. Wake the phone."
        mkdir -p "$shots"
        out="$shots/${1:-shot}-$which-$(date +%H%M%S).png"
        adb -s "$dev" exec-out screencap -p -d "$id" > "$out"
        echo "$out"
        ;;
    tap)
        [[ $# -eq 2 ]] || die "tap needs x and y, in the pixels of a screenshot."
        dev=$(connect); read -r id which < <(screen "$dev")
        [[ -n "${id:-}" ]] || die "no screen is on. Wake the phone."
        # Without -d a tap goes to the focused display, which is the one that is on. input's -d wants a logical display
        # id, not the uniqueId screencap takes, and passing the wrong kind is refused.
        adb -s "$dev" shell input tap "$1" "$2"
        echo "tapped $1,$2 on the $which screen"
        ;;
    *) die "unknown command $cmd (connect, install, screen, shot, tap)." ;;
esac
