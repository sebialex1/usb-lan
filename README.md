# USB LAN

Android app that links two phones over a USB-C cable and gives them a small IPv4 LAN. Any app on either phone can use it: the phones get `10.7.0.1` and `10.7.0.2` on `10.7.0.0/24`.

Status: experimental. The app builds; phone-to-phone operation has not been tested on hardware.

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

A manual GitHub Actions workflow (`.github/workflows/build.yml`) builds the APK and publishes it as a prerelease for a given tag.
