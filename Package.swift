// swift-tools-version:5.9
//
// SPM manifest for iOS consumers of shared (Package.swift lives in the KMP repo).
// SPM does not compile Kotlin: this is just a binaryTarget pointing at the prebuilt
// Shared.xcframework.zip in the GitHub Release. Apps add:
//   https://github.com/Kaktotaks/KMPBrownfield-shared  (Up to Next Minor / Exact Version)
//
// Do NOT edit `version` and `checksum` by hand: .github/workflows/release.yml rewrites them
// before tagging (the checksum is only known after the zip is built: "commit after build").
import PackageDescription
import Foundation

let version = "0.6.0"
let checksum = "c2f8760b5da84911175c69e9069907a974cb378f731ed60fdac95beba30fb3c9"

// Local development: if a debug XCFramework exists next to the manifest
// (./gradlew assembleSharedDebugXCFramework), the package uses it instead of the release zip.
// SPM checkouts (DerivedData) have no build/ folder, so consumers always get the release.
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
