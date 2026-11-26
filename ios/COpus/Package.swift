// swift-tools-version:5.9
// libopus 1.5.2 (BSD-3-Clause, see Sources/COpus/COPYING), vendored unmodified, plus a small Swift-callable shim.
import PackageDescription

let package = Package(
    name: "COpus",
    platforms: [.iOS("18.0")],
    products: [.library(name: "COpus", targets: ["COpus"])],
    targets: [
        .target(
            name: "COpus",
            path: "Sources/COpus",
            exclude: ["COPYING", "AUTHORS", "UPSTREAM.txt"],
            publicHeadersPath: "include",
            cSettings: [
                .define("OPUS_BUILD"), .define("VAR_ARRAYS"), .define("HAVE_LRINT"), .define("HAVE_LRINTF"),
                .headerSearchPath("celt"), .headerSearchPath("silk"), .headerSearchPath("silk/float"),
                .headerSearchPath("src"), .headerSearchPath("include"),
            ]
        ),
    ]
)
