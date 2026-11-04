MotionSpec: splash timeline shared by iOS and Android. **No breaking changes**, purely additive.

## What's new in 0.8.0
- **`MotionSpec` (package `com.example.tasks.shared.motion`).** The animated splash is driven entirely
  from shared: both apps only measure elapsed time, call `MotionSpec.Splash.frameAt(elapsedMs,
  exitStartMs, reduceMotion)` and draw the returned `SplashFrame`. There are no timing numbers, curves
  or spring parameters in app code.
  - `MotionSpec.emphasized`: the app-wide `cubic-bezier(0.2, 0, 0, 1)` easing (`CubicBezier`).
  - `MotionSpec.Splash`: timeline constants (card draw, disc spring, check draw, wordmark and caption
    rise, hold 1.5 to 2.5 s, content fade, circular reveal), mark geometry constants, `exitStartMs(readyAtMs,
    elapsedMs)` and `discCenterInMark()`.
  - `SpringParams`: under-damped analytic spring (`valueAt(seconds)`, `stiffness`), matching SwiftUI
    `.spring(response:dampingFraction:)` and Compose `spring(dampingRatio, stiffness)`.
  - `SplashFrame`: every per-frame value, including the reduced-motion path (static mark, 200 ms fade).
- **Swift API.** `MotionSpec.Splash.shared.frameAt(elapsedMs:exitStartMs:reduceMotion:)`; the nullable
  `Long` parameters arrive as `KotlinLong?`. See `docs/motion-spec-swift-api.md` for the exact
  signatures and a small `Int64?` extension.

## Fixes
None.

## Breaking changes
None.

## Requirements
Unchanged from 0.7.0: iOS 15+, Android minSdk 24.

## How to consume
See the [README](https://github.com/Kaktotaks/KMPBrownfield-shared#readme): iOS via Swift Package
Manager (product `Shared`), Android via `com.example.tasks:shared:0.8.0` from GitHub Packages.
