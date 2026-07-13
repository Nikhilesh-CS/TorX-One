# Installation Guide

This guide explains how to install TorX One on Android.

## Download

Use the latest GitHub Release:

https://github.com/Nikhilesh-CS/TorX-One/releases/latest

Download the APK asset from the release page.

## Android installation

1. Download the latest APK.
2. Open the APK on your Android device.
3. Allow installation from the browser or file manager if Android asks.
4. Install the app.
5. Open TorX One.
6. Complete identity setup.
7. Wait for Tor status to show connected.

## Required permissions

TorX One may request:

- Network access for Tor bootstrap and onion service communication
- Notification permission for background service visibility
- Nearby/Bluetooth permissions for local mesh transport
- Camera permission for QR contact scanning
- File access through Android document picker for identity backup and restore

## Recommended testing device

Use a physical Android device. Emulators can be useful for UI checks, but Tor startup, Bluetooth, Wi-Fi Direct, camera scanning, and background behavior are more reliable to test on real hardware.

## Updating

TorX One checks GitHub Releases for newer APK builds.

The updater expects releases under:

https://github.com/Nikhilesh-CS/TorX-One/releases/latest

## Uninstalling

Uninstalling the app removes local app data unless Android or the device vendor preserves it. Before uninstalling, export an identity backup if you want to restore the same identity later.

