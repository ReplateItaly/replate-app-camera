# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a React Native library called `replate-camera` that provides AR camera functionality for the Replate Manager app. It's a native module that bridges between React Native and native iOS/Android camera implementations with AR capabilities.

## Development Commands

### Core Commands
- `yarn test` - Run Jest tests
- `yarn typecheck` - Run TypeScript type checking
- `yarn lint` - Run ESLint on all TypeScript/JavaScript files
- `yarn clean` - Clean build artifacts from android/ios/lib directories
- `yarn prepare` - Build the library using react-native-builder-bob
- `yarn release` - Create a release using release-it

### Example App
- `yarn example` - Access the example app workspace
- `yarn example start` - Start Metro bundler for the example app
- `yarn example android` - Run example app on Android
- `yarn example ios` - Run example app on iOS

### Library Build
The library uses `react-native-builder-bob` to build multiple output formats:
- CommonJS (`lib/commonjs/`)
- ES modules (`lib/module/`)
- TypeScript definitions (`lib/typescript/`)

## Architecture

### Core Components
- **ReplateCameraView**: Main camera component that renders the AR camera view
- **ReplateCameraModule**: Native module bridge providing camera control functions
- **Native implementations**: 
  - iOS: Swift implementation in `ios/` directory with ARKit integration
  - Android: Kotlin implementation in `android/src/main/java/com/replatecamera/`

### Key Functions
The library exports several camera control functions:
- `takePhoto(unlimited: Boolean)`: Capture photos with optional unlimited mode
- `getPhotosCount()`: Get current photo count
- `getRemainingAnglesToScan()`: Get remaining angles for scanning
- `isScanComplete()`: Check if scanning is complete
- `reset()`: Reset camera state
- `getMemoryUsage()`: Monitor memory usage

### Callback System
Multiple callback registration functions for different camera events:
- Tutorial completion/opening
- Anchor setting
- Sphere scanning completion (upper/lower)
- Distance feedback (too far/close, back in range)

### File Structure
- `src/index.tsx`: Main TypeScript entry point and React Native bridge
- `android/`: Android native implementation (Kotlin)
- `ios/`: iOS native implementation (Swift) with ARKit
- `example/`: Example React Native app demonstrating usage
- `lib/`: Generated build output (excluded from git)

## Testing

Uses Jest with React Native preset. Tests are located in `src/__tests__/`.

## Code Style

- Uses ESLint with React Native config and Prettier
- TypeScript with strict mode enabled
- Prettier configuration: single quotes, 2-space tabs, trailing commas (ES5)
- Conventional commits enforced via commitlint

## Release Process

Uses `release-it` with conventional changelog generation. Releases are automatically published to npm and GitHub.