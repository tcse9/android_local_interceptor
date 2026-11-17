#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "Usage: $0 VERSION GPG_KEY_FINGERPRINT" >&2
  exit 1
fi

version="$1"
fingerprint="$2"
if [[ ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "Use a release version such as 0.1.0." >&2
  exit 1
fi
if [[ ! "$fingerprint" =~ ^[[:xdigit:]]{40}$ && ! "$fingerprint" =~ ^[[:xdigit:]]{64}$ ]]; then
  echo "Use the full GPG key fingerprint." >&2
  exit 1
fi

cd "$(dirname "$0")/.."
command -v gpg >/dev/null
command -v python3 >/dev/null
gpg --list-secret-keys "$fingerprint" >/dev/null

# Build from a clean staging directory so an older release cannot enter the ZIP.
./gradlew :inspector:clean :inspector:publishReleasePublicationToCentralBundleRepository "-PreleaseVersion=$version"
prefix="inspector/build/central-repo/io/github/tcse9/inspector/$version/inspector-$version"
if [[ -t 0 ]]; then
  export GPG_TTY="$(tty)"
fi
for suffix in .aar .pom .module -sources.jar -javadoc.jar; do
  gpg --yes --armor --detach-sign --local-user "$fingerprint" "$prefix$suffix"
done

python3 scripts/bundle-central.py "$version"
