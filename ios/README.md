# TorX One - iOS Client

This is the native iOS client for TorX One, built with Swift and SwiftUI.

## Important status note

The intended TorX One architecture is now:

- nearby peer-to-peer messaging
- nearby multi-hop forwarding
- Tor hidden services for long-distance messaging

The current iOS networking code in this folder still uses an **older WebSocket relay model** and is **not yet aligned** with the current Tor-first architecture described in the root `README.md` and `docs/PROTOCOL.md`.

## Prerequisites
- macOS
- Xcode 15+
- iOS 16.0+ Simulator or Device

## Setup Instructions

Since this codebase was generated on a Windows machine, the Xcode project file (`.xcodeproj`) itself could not be generated. You need to create an Xcode project and add these source files to it.

1. **Create the Project:**
   - Open Xcode and select **Create a new Xcode project**.
   - Select **iOS > App** and click Next.
   - Product Name: `TorX One`
   - Interface: **SwiftUI**
   - Language: **Swift**
   - Save the project to a temporary location, then move the `TorX One.xcodeproj` file into this directory (`astra-mesh/ios/`).

2. **Add Source Files:**
   - Drag all the folders (`Crypto`, `Models`, `Network`, `Storage`, `ViewModels`, `Views`) and the Swift files (`TorX OneApp.swift`, `ContentView.swift`) into your Xcode project navigator.
   - Choose "Create groups" and make sure the `TorX One` target is checked.

3. **Install Dependencies (Swift Package Manager):**
   - In Xcode, go to **File > Add Package Dependencies...**
   - Add **swift-sodium**: 
     - URL: `https://github.com/jedisct1/swift-sodium.git`
     - Version Rule: Up to Next Major `0.9.1`
   - Add **GRDB.swift**:
     - URL: `https://github.com/groue/GRDB.swift.git`
     - Version Rule: Up to Next Major `6.24.0`

4. **Configure Info.plist:**
   - Replace the auto-generated `Info.plist` with the one provided in this directory, or manually ensure that App Transport Security allows local networking if testing with a local relay over `ws://` (non-TLS).

## Running the App

1. Create the Xcode project and add the source files as described above.
2. Build and run it in Xcode.
3. Treat the current build as a UI and crypto baseline, not the finished transport implementation.

## Current migration work

The iOS client needs these transport changes:

1. Remove the required dependency on the Node.js WebSocket relay.
2. Add Tor-based distant messaging support compatible with the shared protocol.
3. Support the current contact string format, including the optional `.onion` field.
4. Implement peer frames compatible with `hello`, `msg`, and `relay`.

Until that migration lands, this folder should be treated as **partially outdated** with respect to networking.
