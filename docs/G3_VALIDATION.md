# DiPlay G3 validation

Local validation on 2026-10-14, macOS ARM64, JDK 25:

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:lintDebug :mobile:assembleDebug
python3 scripts/check_public_tree.py
```

- Shared: 1,379 passed, one pre-existing host-dependent socket-binding assumption skipped.
- Common: 806 passed, no failures or skips.
- Android lint: no errors, 19 warnings. The Play Store expired-target check is explicitly disabled because this is a directly installed Android 9 vehicle build.
- Source-only debug APK compiled successfully; it intentionally contains no standalone authentication inputs.
- Compact and full layouts were rendered at 1.3 font scale; English, Simplified Chinese, and RTL alignment inspected.
- Independent read-only review completed. Hotspot startup now rereads the active credentials before opening projection, with regression coverage for OEM credential changes and failed final reads.
- Public-source guard passed after staging all new files; private authentication inputs and APKs remain outside tracked source.

Hotspot tests cover validated input, permission preflight, explicit restart confirmation, preservation of radio settings, OEM cache synchronization, post-write verification and rollback, passive ADB access, scoped explicit authorization, bounded operations, and malformed shell payload rejection.

The separately built local standalone test APK uses explicitly supplied runtime authentication assets. These assets are not provided in this repository. Do not publish that APK or its inputs with the source.

## Pending vehicle validation

The user left the vehicle before this build. No device changes were made during local completion.
The following remain unverified on the actual D21:

1. Installation, launcher visibility, and local ADB authorization for the dedicated package.
2. Reading and changing the real hotspot name/password, restarting it, and reconnecting clients.
3. Configuration consistency after the OEM projection assistant restarts; its explicit password-refresh operation still changes the password.
4. iPhone pairing, standalone authentication, CarPlay video/audio, and touch input.
5. Real reboot startup. This option does not establish persistence through vehicle sleep.

Unit tests and compilation do not establish these physical-device outcomes.
