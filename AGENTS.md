# DiPlay G3 development

This fork targets XiaoPeng G3 / D21 Android 9 and has one supported route:
WIRELESS + MANUAL vehicle hotspot + LOCAL authentication.
Do not add other connectivity modes or vehicle-specific integrations without user authorization.

Run the same retained-module command as CI before reporting completion:

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:lintDebug :mobile:assembleDebug
```

The standalone car build requires explicit external authentication inputs;
ordinary source/CI builds never include them. Never commit APKs, credentials,
real Wi-Fi passwords, signing keys, or private runtime authentication assets.
Run `python3 scripts/check_public_tree.py` before pushing.

Hotspot changes must require explicit confirmation, synchronize the OEM cache,
preserve radio parameters, verify readback, and attempt rollback on failure.
Never request ADB authorization from background work. The fixed shell helper may
only read or write validated hotspot configuration, never accept arbitrary commands.

Keep the UI grouped by driver goals. Connection and hotspot controls belong to
the G3 launcher; preserve the core projection engine. Labels must be present in
English and Simplified Chinese. Use named dp spacing and control-height constants.
Verify compact and full layouts with a large font scale; check RTL alignment.
Physical G3 hotspot/CarPlay behavior requires an on-device test and must be
reported separately from unit tests and compilation.
