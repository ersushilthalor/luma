# Contributing

Luma is licensed under the [MIT License](LICENSE). Contributions are accepted under the same license. This document describes how work is done in the repository.

## Principles

- **No fake functionality.** A control or mode either works on the device or is shown as unavailable with a reason.
- **No unverified claims.** Hardware capabilities, performance numbers and quality comparisons are stated only after they have been measured. Update the status labels in `spec.md` when something is verified.
- **Small dependency set.** New dependencies need a review entry in `docs/DEPENDENCIES_AND_LICENSES.md` (maintenance, license, size, alternatives).
- **Decisions are recorded.** Significant technical choices get an entry in `docs/TECHNICAL_DECISIONS.md`.

## Workflow

1. Branch from `main` (`feature/<topic>` or `fix/<topic>`).
2. Before opening a pull request:
   ```bash
   ./gradlew assembleDebug assembleRelease lint testDebugUnitTest
   ```
3. Engine changes: run the regression corpus (once it exists, Milestone 7) and include metric deltas.
4. Camera changes: state the device and firmware they were tested on.
5. Keep commits focused. Write messages in the imperative mood ("Add lens catalog").

## Code style

- Kotlin: official Kotlin style (`kotlin.code.style=official`), enforced by `.editorconfig`.
- C++: C++20, warnings enabled (`-Wall -Wextra -Wpedantic` and more in `engine/`). No exceptions across the JNI boundary. Validate every size taken from camera metadata.
- Comments explain *why*, not *what*. No commented-out code.
- No logging of image data or location.

## Personal devices

A personal phone used for discovery is a **read-only inspection target**. Only read-only ADB queries are allowed (see `tools/hardware-discovery/collect.sh`). Installing, pushing or pulling files, changing settings or permissions, and capture tests each need the owner's explicit authorization for that phase (`docs/HARDWARE_VALIDATION_PLAN.md`).

## Device data

Hardware reports (`hardware-reports/`) and benchmark captures (`benchmarks/captures/`) are git-ignored. Review them for personal data before attaching them to issues.
