// swift-tools-version: 5.9

import PackageDescription

let package = Package(
    name: "XCTestLeaksClient",
    platforms: [.iOS(.v13)],
    products: [
        .library(
            name: "XCTestLeaksClient",
            targets: ["XCTestLeaksClient"]
        ),
    ],
    targets: [
        .target(
            name: "XCTestLeaksClient",
            linkerSettings: [
                .linkedFramework("XCTest", .when(platforms: [.iOS])),
            ]
        ),
    ]
)
