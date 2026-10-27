#!/usr/bin/env bash
# One-time: create the release key that signs both apps, and print what goes into GitHub and Google Cloud.
# Usage: scripts/make-release-key.sh [keystore path relative to the repo root, default release.jks (gitignored)]
# Keep the keystore and its password safe (password manager + offline copy): losing it means installed
# phones can never be updated again.
set -euo pipefail

cd "$(dirname "$0")/.."
KEYSTORE="${1:-release.jks}"
ALIAS="tap-and-play"

if [ -e "$KEYSTORE" ]; then
  echo "$KEYSTORE already exists; refusing to overwrite it." >&2
  exit 1
fi

read -rsp "New keystore password (6+ chars): " PASSWORD; echo
read -rsp "Again: " PASSWORD2; echo
[ "$PASSWORD" = "$PASSWORD2" ] || { echo "Passwords differ." >&2; exit 1; }

# PKCS12 has a single password for store and key.
keytool -genkeypair -v -storetype PKCS12 -keystore "$KEYSTORE" -alias "$ALIAS" \
  -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Tap and Play" \
  -storepass "$PASSWORD" -keypass "$PASSWORD" >/dev/null 2>&1

CERT_DER=$(mktemp)
trap 'rm -f "$CERT_DER"' EXIT
keytool -exportcert -keystore "$KEYSTORE" -alias "$ALIAS" -storepass "$PASSWORD" -file "$CERT_DER" >/dev/null 2>&1
SHA1=$(openssl dgst -sha1 -c "$CERT_DER" | awk '{print toupper($2)}')
CHECKSUM=$(openssl dgst -sha256 -binary "$CERT_DER" | openssl base64 -A | tr '+/' '-_' | tr -d '=')

cat <<EOF

Created $KEYSTORE (alias $ALIAS).

GitHub -> Settings -> Secrets and variables -> Actions -> New repository secret:
  TAP_KEYSTORE_BASE64   the line below
  TAP_KEYSTORE_PASSWORD the password you just typed
  TAP_KEY_ALIAS         $ALIAS
  TAP_KEY_PASSWORD      the same password

TAP_KEYSTORE_BASE64:
$(openssl base64 -A -in "$KEYSTORE")

SHA-1 (Google Cloud Console -> Credentials -> OAuth client ID -> Android,
package com.omrihak.nfccards.companion, same project as the debug one):
  $SHA1

Signature checksum (SHA-256, base64url, no padding), for manager/config.js -> releaseSignatureChecksum:
  $CHECKSUM

Local release build:
  TAP_KEYSTORE_FILE=$KEYSTORE TAP_KEYSTORE_PASSWORD=... TAP_KEY_ALIAS=$ALIAS TAP_KEY_PASSWORD=... ./gradlew assembleRelease
EOF
