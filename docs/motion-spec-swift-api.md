# MotionSpec: Swift API (SKIE)

Source: `src/commonMain/kotlin/com/example/tasks/shared/motion/MotionSpec.kt`. Signatures below were
checked by type-checking against the debug XCFramework (`Shared.h` + swiftinterface).

## Access path

`MotionSpec.Splash` is exported as its own class (Swift name `MotionSpec.Splash`) with a `shared`
singleton. Constants are instance properties on it, not static members.

```swift
let splash = MotionSpec.Splash.shared      // NOT MotionSpec.shared.Splash
let easing = MotionSpec.shared.emphasized  // CubicBezier
```

## Signatures

```swift
// MotionSpec.Splash.shared
func frameAt(elapsedMs: Int64, exitStartMs: KotlinLong?, reduceMotion: Bool) -> SplashFrame
func exitStartMs(readyAtMs: KotlinLong?, elapsedMs: Int64) -> KotlinLong?
func discCenterInMark() -> KotlinPair<KotlinFloat, KotlinFloat>   // .first / .second, then .floatValue
var discSpring: SpringParams { get }

// Constants (instance properties of MotionSpec.Splash.shared)
// Int64: CARD_START_MS, CARD_DURATION_MS, DISC_START_MS, CHECK_START_MS, CHECK_DURATION_MS,
//        WORDMARK_START_MS, WORDMARK_DURATION_MS, CAPTION_START_MS, CAPTION_DURATION_MS,
//        MIN_HOLD_MS, MAX_HOLD_MS, CONTENT_FADE_MS, REVEAL_DELAY_MS, REVEAL_DURATION_MS, REDUCED_FADE_MS
// Float: WORDMARK_OFFSET, CAPTION_OFFSET, EXIT_SCALE, MARK_WIDTH, MARK_HEIGHT, BLOCK_HEIGHT,
//        WORDMARK_TOP, CAPTION_TOP, VIEWBOX_X/Y/W/H, DISC_CX, DISC_CY

// SplashFrame (all `let`): Float: cardTrim, discScale, checkTrim, wordmarkAlpha, wordmarkOffsetY,
//   captionAlpha, captionOffsetY, contentAlpha, contentScale, revealFraction, overlayAlpha; Bool: finished
// SpringParams: dampingRatio: Float, responseSeconds: Float, stiffness: Float
// CubicBezier:  x1, y1, x2, y2: Float; func transform(fraction: Float) -> Float
```

Kotlin `Float` is Swift `Float`, `Long` is `Int64`, `Boolean` is `Bool`. Only the nullable `Long`s
box to `KotlinLong?` (`KotlinLong(value:)`, `.int64Value`).

## Suggested Swift extension (Int64?)

Do not reuse the Kotlin labels for the overloads: `frameAt(elapsedMs:exitStartMs:reduceMotion:)`
with an `Int64?` overload makes `exitStartMs: nil` ambiguous (compile error). Use distinct names.

```swift
import Shared

extension MotionSpec.Splash {
    func exitStart(readyAt: Int64?, elapsed: Int64) -> Int64? {
        exitStartMs(readyAtMs: readyAt.map(KotlinLong.init(value:)), elapsedMs: elapsed)?.int64Value
    }

    func frame(at elapsed: Int64, exitStart: Int64?, reduceMotion: Bool) -> SplashFrame {
        frameAt(elapsedMs: elapsed, exitStartMs: exitStart.map(KotlinLong.init(value:)), reduceMotion: reduceMotion)
    }
}
```

## Notes

- `discCenterInMark()` returns (80.939, 51.104), i.e. `(604-176)*132/698`, `(430-160)*120/634`.
  The handoff text rounds this to (80.92, 51.10), which is 0.02 off; the formula wins.
- `frameAt` is a pure function of its inputs: allocate nothing per frame beyond the returned
  `SplashFrame`, and keep timing logic out of Swift.
