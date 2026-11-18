#!/usr/bin/env bash
# Read-only hardware discovery for one connected, already-authorized Android device.
#
# Safety contract: this script only runs read-only queries. It never installs, uninstalls, pushes,
# pulls, changes settings, starts activities, reboots, or touches user data. It does not change
# USB or debugging settings. If the device is not authorized, it stops.
#
# Output: hardware-reports/<model>-<build>-<date>/ (git-ignored). The camera service dump is
# sanitized locally: its runtime event log and client history (which can name apps) are dropped.
set -euo pipefail
export MSYS_NO_PATHCONV=1  # keep device paths intact under Git Bash on Windows

ADB="${ADB:-adb}"
here="$(cd "$(dirname "$0")" && pwd)"

state="$("$ADB" get-state 2>/dev/null || true)"
if [[ "$state" != "device" ]]; then
    echo "Device state is '${state:-none}'. Connect one device, approve the USB debugging prompt on the phone, and retry." >&2
    exit 1
fi

prop() { "$ADB" shell getprop "$1" | tr -d '\r'; }

model="$(prop ro.product.model)"
build="$(prop ro.build.version.incremental)"
out="${1:-hardware-reports/${model}-${build}-$(date +%Y%m%d)}"
mkdir -p "$out"

{
    for key in ro.product.manufacturer ro.product.model ro.product.device ro.product.name \
        ro.build.version.release ro.build.version.sdk ro.build.version.oneui ro.build.display.id \
        ro.build.fingerprint ro.build.version.security_patch ro.vendor.build.fingerprint \
        ro.product.first_api_level ro.vendor.api_level ro.soc.manufacturer ro.soc.model \
        ro.board.platform ro.product.cpu.abilist ro.hardware.vulkan ro.opengles.version \
        ro.camerax.extensions.enabled ro.camera.disableJpegR ro.camera.disableHeicUltraHDR \
        persist.vendor.camera.adaptivelensmode3p vendor.camera.aux.packagelist; do
        echo "$key=$(prop "$key")"
    done
} > "$out/properties.txt"

"$ADB" shell dumpsys media.camera | tr -d '\r' | awk '
    /^Allowed user IDs|^Active Client listener PIDs/ { next }
    /^== Service global info/ { keep = 1 }
    /^== Camera service events log/ { keep = 0 }
    /^== Camera Provider HAL/ { keep = 1 }
    /^== Camera error traces/ { keep = 0 }
    keep { print }
' > "$out/dumpsys-media.camera.static.txt"

"$ADB" shell pm list features | tr -d '\r' > "$out/features.txt"
"$ADB" shell cmd gpu vkjson | tr -d '\r' > "$out/vulkan.json"
{
    "$ADB" shell cat /proc/meminfo | tr -d '\r' | grep -E '^(MemTotal|SwapTotal):'
    echo "cpus: $("$ADB" shell cat /sys/devices/system/cpu/possible | tr -d '\r')"
    "$ADB" shell df -h /data | tr -d '\r'
} > "$out/system.txt"

python3 -I "$here/parse_camera_dump.py" "$out/dumpsys-media.camera.static.txt" \
    --json "$out/camera-summary.json" > "$out/camera-summary.txt"

echo "Read-only hardware report written to $out"
