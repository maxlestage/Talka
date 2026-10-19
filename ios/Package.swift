// swift-tools-version:5.9
// TalkaKit : le cœur de l'application iOS de Talka — les codes, le
// chiffrement (ECDH P-256 + AES-256-GCM, ECDSA), le protocole du relais et la
// logique des discussions. Sans interface : testé aussi sous Linux, contre les
// vecteurs produits par le navigateur (le même code Rust) et le vrai serveur.
import PackageDescription

let package = Package(
    name: "TalkaKit",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "TalkaKit", targets: ["TalkaKit"]),
    ],
    dependencies: [
        // CryptoKit sur iPhone ; la même API (swift-crypto) ailleurs.
        .package(url: "https://github.com/apple/swift-crypto.git", "3.0.0"..<"4.0.0"),
    ],
    targets: [
        .target(
            name: "TalkaKit",
            dependencies: [
                .target(name: "LinuxShims", condition: .when(platforms: [.linux])),
                .product(
                    name: "Crypto", package: "swift-crypto",
                    condition: .when(platforms: [.linux])),
            ]),
        // Sous Linux seulement (les tests) : un symbole que la bibliothèque
        // Observation réclame sans le fournir. Rien de tout cela sur iPhone.
        .target(name: "LinuxShims"),
        .testTarget(
            name: "TalkaKitTests", dependencies: ["TalkaKit"], resources: [.copy("vectors.json")]),
    ]
)
