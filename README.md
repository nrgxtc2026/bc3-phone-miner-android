# BC3 Miner Android

Android miner for Bitcoin III (BC3), with profile management, configurable
CPU mining, safety controls, live mining statistics, and landscape network
dashboards.

Author: nrgxtc

## Current status

- Android UI, persistent foreground service, wake lock, local profiles, manual start/stop controls, and live miner output are implemented.
- Temperature and battery safety limits can pause mining and resume after their configured cooldowns.
- Landscape mode includes miner, current-mining-device, and BC3 network information blades.
- The packaged miner currently targets ARM64 Android phones.
- The optional donation checkbox is off by default and discloses its 2.5% schedule.

## Build

Open this directory in Android Studio, allow Gradle sync, then build the debug APK.
The private local signing key is intentionally excluded; a clean clone uses
Android's standard generated debug key.

## Licensing

The native cpuminer source is GPL-licensed. Preserve its source offer and license notices when distributing the APK. The logo is supplied by the project owner under their asserted open-source permission.
