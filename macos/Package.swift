// swift-tools-version:6.0
// 织文输入法 macOS 版。 WeaveText for macOS.
//
// 内核静态库由 scripts/build-app.sh 先编好放到 build/lib/libweave_c.a（通用二进制）。
// The engine static library is built first by scripts/build-app.sh into build/lib/libweave_c.a (universal).
import Foundation
import PackageDescription

let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().path
let engineLib: [LinkerSetting] = [.unsafeFlags(["-L", "\(root)/build/lib"])]

let package = Package(
    name: "WeaveText",
    platforms: [.macOS(.v13)],
    products: [
        .executable(name: "WeaveText", targets: ["WeaveText"]),
    ],
    targets: [
        .systemLibrary(name: "CWeave", path: "Sources/CWeave"),
        .target(name: "WeaveCore", dependencies: ["CWeave"], linkerSettings: engineLib),
        .executableTarget(
            name: "WeaveText",
            dependencies: ["WeaveCore"],
            linkerSettings: engineLib + [
                .linkedFramework("InputMethodKit"),
                .linkedFramework("Carbon"),
                .linkedFramework("ServiceManagement"),
            ]
        ),
        .testTarget(name: "WeaveCoreTests", dependencies: ["WeaveCore"], linkerSettings: engineLib),
    ],
    swiftLanguageModes: [.v5]
)
