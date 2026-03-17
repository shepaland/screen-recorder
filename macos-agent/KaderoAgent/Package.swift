// swift-tools-version: 5.10
import PackageDescription

let package = Package(
    name: "KaderoAgent",
    platforms: [
        .macOS(.v13)
    ],
    targets: [
        .target(
            name: "KaderoAgent",
            path: "Sources/KaderoAgent"
        ),
        .executableTarget(
            name: "KaderoAgentCLI",
            dependencies: ["KaderoAgent"],
            path: "Sources/KaderoAgentCLI"
        ),
        .testTarget(
            name: "KaderoAgentTests",
            dependencies: ["KaderoAgent"],
            path: "Tests/KaderoAgentTests"
        )
    ]
)
