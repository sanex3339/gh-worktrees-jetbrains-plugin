#!/usr/bin/env bash
# Builds the plugin, signs it with the key in ~/.jetbrains-signing (the same key as gh-stack) and verifies the
# signature. Asks for the key's pass phrase unless PRIVATE_KEY_PASSWORD is already set; it never ends up in shell
# history.
#
#   ./scripts/sign-plugin.sh            # → build/distributions/worktree-tabs-<version>-signed.zip
#   ./scripts/sign-plugin.sh --offline  # extra arguments go to Gradle
#
# JETBRAINS_SIGNING_DIR points at another key folder; IDE_LOCAL_PATH at another installed IDE to build against.
set -euo pipefail

cd "$(dirname "$0")/.."

key_dir="${JETBRAINS_SIGNING_DIR:-$HOME/.jetbrains-signing}"
for file in chain.crt private.pem; do
  if [[ ! -f "$key_dir/$file" ]]; then
    echo "Missing $key_dir/$file (set JETBRAINS_SIGNING_DIR to use another folder)." >&2
    exit 1
  fi
done

if [[ -z "${PRIVATE_KEY_PASSWORD:-}" ]]; then
  read -r -s -p "Key password: " PRIVATE_KEY_PASSWORD
  echo
fi

# Build against an installed WebStorm when there is one, instead of downloading the IDE.
ide_args=()
for ide in "${IDE_LOCAL_PATH:-}" "$HOME/Applications/WebStorm.app" "/Applications/WebStorm.app"; do
  if [[ -n "$ide" && -d "$ide" ]]; then
    ide_args=("-PideLocalPath=$ide")
    break
  fi
done

CERTIFICATE_CHAIN="$(cat "$key_dir/chain.crt")" \
PRIVATE_KEY="$(cat "$key_dir/private.pem")" \
PRIVATE_KEY_PASSWORD="$PRIVATE_KEY_PASSWORD" \
  ./gradlew clean signPlugin verifyPluginSignature ${ide_args[@]+"${ide_args[@]}"} "$@"

echo
echo "Signed: $(ls build/distributions/*-signed.zip)"
