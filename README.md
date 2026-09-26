# mobile-ads-blocker

A modern mobile ad blocker built with Kotlin that provides a local VPN service to capture DNS queries, block ad domains with NXDOMAIN responses, and track per-app block statistics.

## Features
- Establishes a local VPN service that captures DNS queries using a fake DNS address (10.0.0.1) while letting all other traffic pass untouched
- Parses minimal IPv4, UDP, and DNS packets to inspect domain names and match them against a loaded blocklist
- Returns NXDOMAIN responses for listed ad domains and forwards valid queries to a real DNS server
- Tracks blocked-request counters per app, maintains recent block history, and persists statistics
- Provides an interactive user interface with animated state indicators and detailed statistics views

## Tech Stack
- Kotlin
- Android SDK (VpnService, Local VPN, Network APIs)

## Installation
```bash
git clone https://github.com/xreactivee/mobile-ads-blocker.git
cd mobile-ads-blocker
./gradlew build
```

## Usage
Open the project in Android Studio, build the APK, and install it on an Android device. Launch the app and tap the power button to start the local VPN service and begin blocking ads.

## License
MIT