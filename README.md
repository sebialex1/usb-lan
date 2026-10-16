# USB LAN

Android app that links two phones over a USB-C cable and gives them a small IPv4 LAN. Any app on either phone can use it: the phones get `10.7.0.1` and `10.7.0.2` on `10.7.0.0/24`.

Status: experimental. The app builds and releases are signed. Phone-to-phone operation has not been tested on hardware.

## How it works

- One phone takes the USB host role. It uses the Android Open Accessory (AOA) protocol (control requests 51/52/53) to switch the other phone into accessory mode.
- The two phones then exchange frames over USB bulk transfers. Each frame is a 2-byte little-endian length, a flag byte (0 = IPv4 packet, 1 = control text) and the payload.
- On each phone a `VpnService` creates a TUN interface (host `10.7.0.1`, accessory `10.7.0.2`, route `10.7.0.0/24`, MTU 1380) and moves IPv4 packets between the TUN and the USB link. Only traffic for `10.7.0.0/24` goes through it; other traffic is untouched.

## Usage

1. Install the same APK on both phones.
2. Open the app on at least one phone.
3. Connect the phones with a USB-C cable.
4. Accept the USB permission prompts, then grant VPN permission when asked (tap the screen if it shows "Tap to allow VPN").
5. "Starting" means this phone's VPN is up and it is waiting for the other phone's. When the screen shows "Connected" (both VPNs are up), use `10.7.0.1` (host phone) and `10.7.0.2` (accessory phone) from any app, e.g. a web server on one phone and a browser on the other.
6. Connection test: both screens show a session number (picked by the host) and "ping X ms" (round trip measured by the host and shared with the accessory). Same session number on both phones plus a ping value means data flows both ways. "ping -" means no reply yet, or the host phone's app is in the background.

Unplugging the cable stops the LAN.

## Updates

Releases are signed with a release key that is kept only in GitHub Actions secrets; it is not in this repository. From v0.3.0 on, a newer release installs over an older one and keeps the app's settings.

- **Coming from v0.2.0 or a debug v0.1.x build: uninstall once.** v0.3.0 is signed with a new key (the v0.2.0 key was published in this repo's history and is retired; v0.1.x builds were debug-signed). Android refuses to install over an app signed with a different key, so the in-app updater cannot move you from those versions either. Uninstall the old app, then install v0.3.0 or later.
- **In-app updates:** tap "Check for updates" at the bottom of the screen. The app reads the newest release (prereleases included) from GitHub. If that release is newer than the installed version, the app downloads its APK and opens the system installer. The first time, Android asks you to allow "Install unknown apps" for USB LAN. Allow it, then tap the button again.

## Playing LAN games

USB LAN routes all of `10.7.0.0/24` for every app on the phone. The VPN does not restrict apps (no allowed/disallowed app lists), so games and launchers such as PojavLauncher or Termux use it like any other network.

1. Connect both phones and wait until both screens show "Connected" with the same session number.
2. Start the game server, or host the world, on one phone.
3. On the other phone, connect by IP to the hosting phone's address: `10.7.0.1` if the host is the USB host phone, `10.7.0.2` if it is the accessory phone. Each screen shows its own address and the peer's.

Minecraft example (Java Edition):

- Run a Java Edition server on phone A (for example in Termux), or open a world in PojavLauncher. A dedicated server listens on port `25565` by default; "Open to LAN" uses the port printed in the chat.
- On phone B, in PojavLauncher choose Multiplayer, Add Server / Direct Connection, and enter phone A's LAN address with the port, e.g. `10.7.0.1:25565`.

Notes:

- The link MTU is 1380, which is fine for Minecraft and other TCP/UDP games.
- Throughput is limited to USB 2.0 bulk transfer speed, plenty for game traffic.
- Game discovery does not cross the link: UDP broadcast, multicast and mDNS ("local network discovery", the LAN worlds list) are not routed. Always connect by IP address directly.

## Requirements

- A USB-C data cable (charge-only cables will not work).
- Android 8.0 (API 26) or newer on both phones.
- IPv4 only.

## Limitations

- Some phones will not take the USB host role on a C-to-C cable. If nothing happens, swap the cable ends, or use a USB OTG adapter on the phone that should be host.
- The VPN slot is exclusive: another active VPN on the same phone will be replaced.
- Phone-to-phone operation is build-verified but not hardware-tested.

## Building from source

Requires JDK 17 and the Android SDK (platform 34, build-tools 34).

```sh
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

A signed release build:

```sh
./gradlew assembleRelease
```

The APK is written to `app/build/outputs/apk/release/app-release.apk`. Signing material is never committed (`keystore/` is git-ignored). The build looks for it in this order:

1. Environment variables (used by CI): `USBLAN_KEYSTORE_BASE64` (the keystore, base64), `USBLAN_KEYSTORE_PASSWORD`, `USBLAN_KEY_ALIAS`, `USBLAN_KEY_PASSWORD`.
2. A local, untracked `keystore/keystore.properties` with `storeFile` (relative to `keystore/`), `storePassword`, `keyAlias`, `keyPassword`.
3. Neither: the release APK is built unsigned. An unsigned or differently signed APK will not install over the published releases.

A manual GitHub Actions workflow (`.github/workflows/build.yml`) builds the release APK, signs it with the key from the repository secrets above, and publishes it as a prerelease. The `tag` input is optional: if it is empty, the workflow uses `v0.3.<run number>`. The workflow sets the app's `versionName` to the tag without the leading `v`, so the in-app update check compares the right versions.
