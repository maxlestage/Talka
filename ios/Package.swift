// swift-tools-version:5.9
// TalkaKit : le cœur de l'application iOS de Talka — les codes, le
// chiffrement (ECDH P-256 + AES-256-GCM, ECDSA), le protocole du relais et la
// logique des discussions, le protocole v3 (sessions à double cliquet, avec
// BrocolisProto). Sans interface : testé aussi sous Linux, contre les
// vecteurs produits par le navigateur (le même code Rust) et le vrai serveur.
import PackageDescription

let package = Package(
    name: "TalkaKit",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "TalkaKit", targets: ["TalkaKit"]),
    ],
    dependencies: [
        // CryptoKit sur iPhone ; la même API (swift-crypto) ailleurs. 4.x est
        // celle de BrocolisProto (ML-KEM sous Linux) : les deux séries vont.
        .package(url: "https://github.com/apple/swift-crypto.git", "3.0.0"..<"5.0.0"),
        // Le protocole v3 : le portage Swift, vérifié octet pour octet, de la
        // crate Rust `brocolis-proto` qu'utilise le navigateur.
        .package(url: "https://github.com/maxlestage/Messagerie-", revision: "002b60136e5951a191f7a5fa2c8692b48044e90b"),
    ],
    targets: [
        .target(
            name: "TalkaKit",
            dependencies: [
                .target(name: "LinuxShims", condition: .when(platforms: [.linux])),
                .target(name: "CSQLite", condition: .when(platforms: [.linux])),
                .product(
                    name: "Crypto", package: "swift-crypto",
                    condition: .when(platforms: [.linux])),
                .product(name: "BrocolisProto", package: "Messagerie-"),
            ]),
        // Sous Linux seulement (les tests) : un symbole que la bibliothèque
        // Observation réclame sans le fournir. Rien de tout cela sur iPhone.
        .target(name: "LinuxShims"),
        // SQLite : fourni par iOS (module SQLite3) ; sous Linux, la
        // bibliothèque du système (libsqlite3).
        .systemLibrary(name: "CSQLite", providers: [.apt(["libsqlite3-dev"])]),
        .testTarget(
            name: "TalkaKitTests",
            dependencies: ["TalkaKit", .product(name: "BrocolisProto", package: "Messagerie-")],
            resources: [.copy("vectors.json")]),
    ]
)
