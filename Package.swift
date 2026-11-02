// swift-tools-version:5.9
//
// ⚡️ SPM-маніфест для iOS-споживачів shared (варіант A: Package.swift живе в репозиторії KMP).
// Kotlin не компілюється через SPM: тут лише binaryTarget, який вказує на готовий
// Shared.xcframework.zip у GitHub Release. Застосунок підключає:
//   https://github.com/Kaktotaks/KMPBrownfield-shared  (Up to Next Minor / Exact Version)
//
// `version` і `checksum` НЕ редагуються руками: їх переписує .github/workflows/release.yml
// перед тим, як поставити тег (checksum відомий лише після збірки zip — "commit-after-build").
import PackageDescription
import Foundation

let version = "0.5.0"
let checksum = "bd9730ab875af751d57dbe874df39f3aab75dd58b861922088628b0bd7e1e84e"

// ⚡️ Локальна розробка: якщо поруч з маніфестом є debug XCFramework
// (./gradlew assembleSharedDebugXCFramework), пакет бере його замість релізного zip.
// У чекауті SPM (DerivedData) папки build/ немає, тож споживачі завжди отримують реліз.
let localXCFramework = "build/XCFrameworks/debug/Shared.xcframework"
let packageDirectory = URL(fileURLWithPath: #filePath).deletingLastPathComponent().path
let useLocalBuild = FileManager.default.fileExists(atPath: "\(packageDirectory)/\(localXCFramework)")

let package = Package(
    name: "Shared",
    platforms: [.iOS(.v15)],
    products: [
        .library(name: "Shared", targets: ["Shared"]),
    ],
    targets: [
        useLocalBuild
            ? .binaryTarget(name: "Shared", path: localXCFramework)
            : .binaryTarget(
                name: "Shared",
                url: "https://github.com/Kaktotaks/KMPBrownfield-shared/releases/download/\(version)/Shared.xcframework.zip",
                checksum: checksum
            ),
    ]
)
