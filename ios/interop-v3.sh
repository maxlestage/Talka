#!/bin/sh
# L'iPhone (TalkaKit, `swift test`) et le navigateur (son code Rust,
# `tests/ios_v3.rs`) en protocole v3, par un vrai relais (`cargo run`) :
#
#     ios/interop-v3.sh
#
# Les deux côtés s'échangent leurs codes et quelques signaux par des fichiers
# (TALKA_V3_INTEROP_DIR) ; chacun vérifie ce que l'autre lui envoie.
set -eu
root=$(cd "$(dirname "$0")/.." && pwd)
port=${PORT:-3917}
dir=$(mktemp -d)
export TALKA_SERVER="http://127.0.0.1:$port" TALKA_V3_INTEROP_DIR="$dir"

# Tout est compilé d'abord : aucun côté n'attend l'autre pour ça.
(cd "$root" && cargo build --quiet && cargo test --quiet --no-run --test ios_v3)
(cd "$root/ios" && swift build --build-tests)

(cd "$root" && PORT=$port exec cargo run --quiet) &
relay=$!
trap 'kill $relay 2>/dev/null || true' EXIT
until curl -s -o /dev/null "$TALKA_SERVER/"; do sleep 0.2; done

(cd "$root" && cargo test --quiet --test ios_v3 -- --nocapture) &
rust=$!
(cd "$root/ios" && swift test --skip-build --filter V3InteropTests) &
swift=$!
status=0
wait $rust || status=1
wait $swift || status=1
if [ $status -eq 0 ]; then echo "✓ iPhone (Swift) et navigateur (Rust) se parlent en v3"; else echo "✗ échec"; fi
exit $status
