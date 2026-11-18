# Security and privacy

Luma processes and stores everything on the device. It has **no network access**: the app does not request `INTERNET`. The release APK requests `CAMERA` and `RECORD_AUDIO` (video), plus an AndroidX-generated, app-internal broadcast permission. MediaPipe (Portrait segmentation) declares `INTERNET` and `ACCESS_NETWORK_STATE`; both are removed from the merged manifest, and its model is bundled in the APK.

## Permissions

| Permission | When | Notes |
|---|---|---|
| `CAMERA` | First launch, before preview | Required. Without it the app shows an explanation and stays usable for gallery and settings |
| `ACCESS_COARSE_LOCATION` / `ACCESS_FINE_LOCATION` | Only when the user turns on location tagging (off by default) | Location from the platform `LocationManager`, with no Google Play services dependency. If the user grants only approximate location, Luma stores only approximate coordinates |
| `READ_MEDIA_IMAGES` / `READ_MEDIA_VISUAL_USER_SELECTED` | Not requested by default | Luma reads back its own MediaStore entries without permission. After a reinstall, ownership is lost. Luma then offers the photo picker or partial media access instead of broad access |
| `RECORD_AUDIO` | When the user first records video | Audio track of videos; recording starts once audio is flowing |
| `INTERNET` | Never | Enforced by a CI check on the release APK |

## Storage

- Photos and DNGs are saved through MediaStore to `DCIM/Luma/` (`image/jpeg`, `image/heic`, `image/x-adobe-dng`). No storage permission is needed on API 29+.
- Writes use `IS_PENDING = 1` until the file is complete, so other apps never see partial files.
- Temporary data (persisted RAW frames for crash-safe processing, intermediates) lives in app-internal `noBackupFilesDir`. Nothing temporary goes to shared storage. Temporary files are deleted when the job completes. Abandoned jobs are deleted after the retention limit (default 7 days).
- Diagnostics reports go to app-specific external storage (`Android/data/com.datarohit.luma/files/diagnostics/`). They are written only on the user's request and are removed on uninstall.
- App data is excluded from cloud backup and device transfer (`data_extraction_rules.xml`). This will be revisited for settings once settings exist.

## Image metadata

| Field | Written | Notes |
|---|---|---|
| Make, model, lens/module description, focal length, 35 mm equivalent, f-number, exposure, ISO, white balance, flash, date/time with offset | Yes | Standard EXIF / DNG |
| Orientation | Yes | |
| GPS | Only if location tagging is enabled | Approximate if only coarse location is granted |
| Luma provenance (XMP): app version, engine version, output class (native / merged / reconstructed / processed), frame count, physical camera, pixel mode, crop | Yes | Supports the honesty rules. Contains no identifiers |
| Device serial numbers, IMEI, account or advertising IDs, unique install IDs | **Never** | |

When sharing from the Luma gallery, a "Remove location" option is on by default when the file contains GPS data.

## Diagnostics and crash information

- No analytics, no crash-reporting SDK, no advertising SDK.
- Crashes: an uncaught-exception handler writes a local crash record to app-internal storage. `ApplicationExitInfo` (API 30) is read on the next start to record native crashes and low-memory kills. Both are visible in Diagnostics and leave the device only if the user exports them.
- Diagnostic reports contain the device model, build fingerprint and camera characteristics. The export screen states this before sharing.
- Logging: no image data and no location in logs. Verbose camera logging only in debug builds.

## Native code

- The engine treats all metadata from the camera HAL as untrusted input. Sizes, strides and counts are validated before use, and all buffer access is bounds-checked against validated dimensions.
- Engine tests run under AddressSanitizer and UndefinedBehaviorSanitizer on the desktop. HWASan builds are used for on-device testing (Milestone 7).
- If Luma later re-opens DNG files (for example to reprocess), the reader accepts only the subset Luma writes, with strict limits on dimensions and IFD counts. It is fuzzed before release.

## Supply chain

- Gradle wrapper checksum pinned. Repository content filters set.
- Proposed: Gradle dependency verification metadata, GitHub Actions pinned by SHA, and a CI check that the merged release manifest contains no `INTERNET` permission.

## Reporting vulnerabilities

See [SECURITY.md](../SECURITY.md).
