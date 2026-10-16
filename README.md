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
5. When the screen shows "Connected", use `10.7.0.1` (host phone) and `10.7.0.2` (accessory phone) from any app, e.g. a web server on one phone and a browser on the other.

Unplugging the cable stops the LAN.

## Updates

Releases from v0.2.0 on are signed with a fixed release key, so a newer release installs over an older one and keeps the app's settings.

- **Coming from v0.1.x:** those builds were debug-signed with a different key. Uninstall the v0.1.x app first, then install the signed release. Android refuses to install over an app signed with a different key.
- **In-app updates:** tap "Check for updates" at the bottom of the screen. The app reads the newest release (prereleases included) from GitHub. If that release is newer than the installed version, the app downloads its APK and opens the system installer. The first time, Android asks you to allow "Install unknown apps" for USB LAN. Allow it, then tap the button again.

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

The APK is written to `app/build/outputs/apk/release/app-release.apk`, signed with `keystore/usblan-release.keystore`. The passwords are in `keystore/keystore.properties`.

**Security note:** the release keystore and its passwords are committed to this repository on purpose, so CI and anyone else can build updates that install over existing installs. It also means anyone can sign an APK that Android accepts as an update to `com.usblan`. That is acceptable for a personal tool. Do not reuse this key for anything else. Install only APKs from this repo's releases.

A manual GitHub Actions workflow (`.github/workflows/build.yml`) builds the signed release APK and publishes it as a prerelease. The `tag` input is optional: if it is empty, the workflow uses `v0.2.<run number>`. The workflow sets the app's `versionName` to the tag without the leading `v`, so the in-app update check compares the right versions.
