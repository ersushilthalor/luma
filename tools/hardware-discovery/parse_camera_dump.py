"""Summarize `adb shell dumpsys media.camera` output (static metadata only).

Usage: python -I parse_camera_dump.py <dumpsys-output.txt> [--json out.json]

Reads a dump produced on the laptop and prints, per HAL camera device and per nested physical
camera, the characteristics Luma needs. Runtime sections (event logs, client history) are ignored.
"""

import json
import re
import sys
from collections import OrderedDict

HAL_FORMATS = {
    0x20: "RAW16", 0x21: "BLOB", 0x22: "IMPL_DEFINED", 0x23: "YUV_420_888", 0x24: "RAW_OPAQUE",
    0x25: "RAW10", 0x26: "RAW12", 0x36: "YCBCR_P010", 0x20203859: "Y8", 0x44363159: "DEPTH16",
}
CAPABILITIES = [
    "BACKWARD_COMPATIBLE", "MANUAL_SENSOR", "MANUAL_POST_PROCESSING", "RAW", "PRIVATE_REPROCESSING",
    "READ_SENSOR_SETTINGS", "BURST_CAPTURE", "YUV_REPROCESSING", "DEPTH_OUTPUT",
    "CONSTRAINED_HIGH_SPEED_VIDEO", "MOTION_TRACKING", "LOGICAL_MULTI_CAMERA", "MONOCHROME",
    "SECURE_IMAGE_DATA", "SYSTEM_CAMERA", "OFFLINE_PROCESSING", "ULTRA_HIGH_RESOLUTION_SENSOR",
    "REMOSAIC_REPROCESSING", "DYNAMIC_RANGE_TEN_BIT", "STREAM_USE_CASE", "COLOR_SPACE_PROFILES",
]
HW_LEVELS = {0: "LIMITED", 1: "FULL", 2: "LEGACY", 3: "LEVEL_3", 4: "EXTERNAL"}
CFA = {0: "RGGB", 1: "GRBG", 2: "GBRG", 3: "BGGR", 4: "RGB", 5: "MONO", 6: "NIR"}
FOCUS_CAL = {0: "UNCALIBRATED", 1: "APPROXIMATE", 2: "CALIBRATED"}
AF_MODES = {0: "OFF", 1: "AUTO", 2: "MACRO", 3: "CONTINUOUS_VIDEO", 4: "CONTINUOUS_PICTURE", 5: "EDOF"}

ENTRY = re.compile(r"^\s+([\w.]+) \(([0-9a-f]+)\): (\w+)\[(\d+)\]\s*$")
DEVICE = re.compile(r"^== Camera HAL device device@[\d.]+/internal/(\d+) .*static information: ==")
PHYSICAL = re.compile(r"^\s+Physical camera (\S+) characteristics:")


def parse_values(lines):
    tokens = []
    for line in lines:
        tokens += re.findall(r"\(\s*-?\d+\s*/\s*-?\d+\s*\)|[^\s\[\]]+", line)
    return tokens


def parse(path):
    devices, current, entries, name, buf = OrderedDict(), None, None, None, []

    def flush():
        if entries is not None and name is not None:
            entries[name] = parse_values(buf)

    with open(path, encoding="utf-8", errors="replace") as handle:
        for raw in handle:
            line = raw.rstrip("\n")
            if line.startswith("== "):
                flush()
                name, buf = None, []
                match = DEVICE.match(line)
                if match:
                    current = match.group(1)
                    devices[current] = OrderedDict(header={}, physical=OrderedDict(), meta=OrderedDict())
                    entries = devices[current]["meta"]
                else:
                    current, entries = None, None
                continue
            if current is None:
                continue
            match = PHYSICAL.match(line)
            if match:
                flush()
                name, buf = None, []
                entries = devices[current]["physical"].setdefault(match.group(1), OrderedDict())
                continue
            match = ENTRY.match(line)
            if match:
                flush()
                name, buf = match.group(1), []
                entries.setdefault("__counts__", {})[name] = int(match.group(4))
                continue
            stripped = line.strip()
            if name is not None and stripped.startswith("["):
                buf.append(stripped)
            elif stripped.startswith(("Facing:", "Orientation:", "Has a flash unit:", "Resource cost:")):
                key, _, value = stripped.partition(":")
                devices[current]["header"][key] = value.strip()
    flush()
    return devices


def ints(meta, key):
    return [int(v) for v in meta.get(key, []) if re.fullmatch(r"-?\d+", v)]


def floats(meta, key):
    return [float(v) for v in meta.get(key, []) if re.fullmatch(r"-?[\d.]+(e-?\d+)?", v)]


def stream_sizes(meta, key, durations_key):
    tokens = meta.get(key, [])
    durations = {}
    dur_tokens = ints(meta, durations_key)
    for i in range(0, len(dur_tokens) - 3, 4):
        durations[tuple(dur_tokens[i:i + 3])] = dur_tokens[i + 3]
    out = {}
    for i in range(0, len(tokens) - 3, 4):
        fmt, width, height, direction = tokens[i:i + 4]
        if direction != "OUTPUT":
            continue
        fmt, width, height = int(fmt), int(width), int(height)
        label = HAL_FORMATS.get(fmt, hex(fmt))
        min_duration = durations.get((fmt, width, height))
        out.setdefault(label, []).append((width, height, min_duration))
    for label in out:
        out[label] = sorted(set(out[label]), key=lambda s: -s[0] * s[1])
    return out


def physical_ids(meta):
    """physicalIds is a NUL-separated byte string; the dump prints its characters and omits the NULs."""
    key = "android.logicalMultiCamera.physicalIds"
    chars = meta.get(key, [])
    count = meta.get("__counts__", {}).get(key, 0)
    ids = count - len(chars)
    if not chars or ids <= 0 or len(chars) % ids:
        return chars
    width = len(chars) // ids
    return ["".join(chars[i:i + width]) for i in range(0, len(chars), width)]


def enum_values(meta, key, names):
    out = []
    for token in meta.get(key, []):
        if re.fullmatch(r"-?\d+", token):
            value = int(token)
            out.append(names[value] if isinstance(names, list) and value < len(names) else names.get(value, token)
                       if isinstance(names, dict) else token)
        else:
            out.append(token)
    return out


def summarize(meta):
    caps = enum_values(meta, "android.request.availableCapabilities", CAPABILITIES)
    level = enum_values(meta, "android.info.supportedHardwareLevel", HW_LEVELS)
    cfa = enum_values(meta, "android.sensor.info.colorFilterArrangement", CFA)
    focus_cal = enum_values(meta, "android.lens.info.focusDistanceCalibration", FOCUS_CAL)
    summary = OrderedDict(
        facing=meta.get("android.lens.facing", []),
        hardware_level=level[0] if level else None,
        capabilities=caps,
        physical_ids=physical_ids(meta),
        focal_lengths_mm=floats(meta, "android.lens.info.availableFocalLengths"),
        apertures=floats(meta, "android.lens.info.availableApertures"),
        min_focus_diopters=floats(meta, "android.lens.info.minimumFocusDistance"),
        hyperfocal_diopters=floats(meta, "android.lens.info.hyperfocalDistance"),
        focus_calibration=focus_cal[0] if focus_cal else None,
        af_modes=enum_values(meta, "android.control.afAvailableModes", AF_MODES),
        ois_modes=meta.get("android.lens.info.availableOpticalStabilization", []),
        physical_size_mm=floats(meta, "android.sensor.info.physicalSize"),
        pixel_array=ints(meta, "android.sensor.info.pixelArraySize"),
        pixel_array_max_res=ints(meta, "android.sensor.info.pixelArraySizeMaximumResolution"),
        active_array=ints(meta, "android.sensor.info.activeArraySize"),
        active_array_max_res=ints(meta, "android.sensor.info.activeArraySizeMaximumResolution"),
        binning_factor=ints(meta, "android.sensor.info.binningFactor"),
        cfa=cfa[0] if cfa else None,
        white_level=ints(meta, "android.sensor.info.whiteLevel"),
        black_level_pattern=ints(meta, "android.sensor.blackLevelPattern"),
        iso_range=ints(meta, "android.sensor.info.sensitivityRange"),
        max_analog_iso=ints(meta, "android.sensor.maxAnalogSensitivity"),
        exposure_range_ns=ints(meta, "android.sensor.info.exposureTimeRange"),
        max_frame_duration_ns=ints(meta, "android.sensor.info.maxFrameDuration"),
        zoom_ratio_range=floats(meta, "android.control.zoomRatioRange"),
        max_digital_zoom=floats(meta, "android.scaler.availableMaxDigitalZoom"),
        ae_compensation_range=ints(meta, "android.control.aeCompensationRange"),
        wb_cct_range=ints(meta, "android.colorCorrection.colorTemperatureRange"),
        ae_priority_modes=ints(meta, "android.control.aeAvailablePriorityModes"),
        settings_overrides=ints(meta, "android.control.availableSettingsOverrides"),
        lens_shading_map_size=ints(meta, "android.lens.info.shadingMapSize"),
        lens_shading_map_modes=ints(meta, "android.statistics.info.availableLensShadingMapModes"),
        hot_pixel_map_modes=ints(meta, "android.statistics.info.availableHotPixelMapModes"),
        face_detect_modes=ints(meta, "android.statistics.info.availableFaceDetectModes"),
        reference_illuminant1=meta.get("android.sensor.referenceIlluminant1", []),
        reference_illuminant2=meta.get("android.sensor.referenceIlluminant2", []),
        has_forward_matrix1=("android.sensor.forwardMatrix1" in meta),
        has_forward_matrix2=("android.sensor.forwardMatrix2" in meta),
        sensor_sync=meta.get("android.logicalMultiCamera.sensorSyncType", []),
        distortion_correction_modes=meta.get("android.distortionCorrection.availableModes", []),
        noise_reduction_modes=meta.get("android.noiseReduction.availableNoiseReductionModes", []),
        edge_modes=meta.get("android.edge.availableEdgeModes", []),
        tonemap_modes=meta.get("android.tonemap.availableToneMapModes", []),
        ae_modes=meta.get("android.control.aeAvailableModes", []),
        awb_modes=meta.get("android.control.awbAvailableModes", []),
        max_regions_ae_awb_af=ints(meta, "android.control.maxRegions"),
        flash_available=meta.get("android.flash.info.available", []),
        timestamp_source=meta.get("android.sensor.info.timestampSource", []),
        readout_timestamp=meta.get("android.sensor.readoutTimestamp", []),
        dynamic_range_profiles=meta.get("android.request.availableDynamicRangeProfilesMap", [])[:3],
        has_color_transform=("android.sensor.colorTransform1" in meta),
        max_output_streams_raw_proc_stall=ints(meta, "android.request.maxNumOutputStreams"),
        streams=stream_sizes(meta, "android.scaler.availableStreamConfigurations",
                             "android.scaler.availableMinFrameDurations"),
        streams_max_res=stream_sizes(meta, "android.scaler.availableStreamConfigurationsMaximumResolution",
                                     "android.scaler.availableMinFrameDurationsMaximumResolution"),
        heic=stream_sizes(meta, "android.heic.availableHeicStreamConfigurations",
                          "android.heic.availableHeicMinFrameDurations").get("BLOB", []),
        jpeg_r=stream_sizes(meta, "android.jpegr.availableJpegRStreamConfigurations",
                            "android.jpegr.availableJpegRMinFrameDurations").get("BLOB", []),
        samsung_tags=sorted(k for k in meta if k.startswith("samsung.")),
        samsung_physical_type=meta.get("samsung.android.sensor.info.physicalType", []),
        samsung_high_resolution_mode=meta.get("samsung.android.sensor.info.highResolutionMode", []),
    )
    return summary


def fmt_sizes(sizes, limit=4):
    def one(s):
        w, h, d = s
        mp = w * h / 1e6
        fps = f" {1e9 / d:.0f}fps" if d else ""
        return f"{w}x{h} ({mp:.1f}MP{fps})"
    head = ", ".join(one(s) for s in sizes[:limit])
    return head + (f" … +{len(sizes) - limit}" if len(sizes) > limit else "")


def main():
    devices = parse(sys.argv[1])
    result = OrderedDict()
    for dev_id, dev in devices.items():
        result[dev_id] = OrderedDict(header=dev["header"], summary=summarize(dev["meta"]),
                                     physical={pid: summarize(m) for pid, m in dev["physical"].items()})
    if "--json" in sys.argv:
        with open(sys.argv[sys.argv.index("--json") + 1], "w", encoding="utf-8") as out:
            json.dump(result, out, indent=1)

    for dev_id, dev in result.items():
        blocks = [(f"device {dev_id}", dev["summary"])] + [
            (f"device {dev_id} / physical {pid}", s) for pid, s in dev["physical"].items()]
        for title, s in blocks:
            print(f"### {title}  {dev['header'] if title.startswith('device ' + dev_id) and '/' not in title else ''}")
            for key in ("facing", "hardware_level", "capabilities", "physical_ids", "sensor_sync",
                        "focal_lengths_mm", "apertures", "min_focus_diopters", "focus_calibration", "af_modes",
                        "ois_modes", "physical_size_mm", "pixel_array", "pixel_array_max_res", "active_array",
                        "active_array_max_res", "binning_factor", "cfa", "white_level", "iso_range",
                        "max_analog_iso", "exposure_range_ns", "max_frame_duration_ns", "zoom_ratio_range",
                        "ae_compensation_range", "wb_cct_range", "ae_priority_modes", "settings_overrides",
                        "lens_shading_map_modes", "hot_pixel_map_modes", "reference_illuminant1",
                        "reference_illuminant2", "has_forward_matrix1", "has_forward_matrix2",
                        "distortion_correction_modes", "noise_reduction_modes", "edge_modes", "tonemap_modes",
                        "ae_modes", "awb_modes", "max_regions_ae_awb_af", "flash_available", "timestamp_source",
                        "readout_timestamp"):
                value = s[key]
                if value not in ([], None, ""):
                    print(f"  {key}: {value}")
            for label in ("RAW16", "RAW10", "RAW12", "RAW_OPAQUE", "BLOB", "YUV_420_888", "YCBCR_P010"):
                if label in s["streams"]:
                    print(f"  {label}: {fmt_sizes(s['streams'][label])}")
                if label in s["streams_max_res"]:
                    print(f"  {label} [max-res]: {fmt_sizes(s['streams_max_res'][label])}")
            if s["heic"]:
                print(f"  HEIC: {fmt_sizes(s['heic'])}")
            if s["jpeg_r"]:
                print(f"  JPEG_R: {fmt_sizes(s['jpeg_r'])}")
        print()


if __name__ == "__main__":
    main()
