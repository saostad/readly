#!/usr/bin/env bash
# Prints the next release version (x.y.z) and its Android versionCode, from the
# newest vX.Y.Z tag and the commit subjects since it (Conventional Commits):
#   "type!:" or a "BREAKING CHANGE" footer -> major, "feat" -> minor, else -> patch.
# With no release tag yet, the first version is the app's starting 0.1.0.
# Output (for $GITHUB_OUTPUT):  version=1.4.2  tag=v1.4.2  code=1004002
set -euo pipefail

first_version="${FIRST_VERSION:-0.1.0}"
ref="${1:-HEAD}"

last_tag="$(git tag --list 'v[0-9]*.[0-9]*.[0-9]*' --sort=-v:refname --merged "$ref" | head -n 1)"

if [[ -z "$last_tag" ]]; then
    version="$first_version"
else
    IFS=. read -r major minor patch <<<"${last_tag#v}"
    range="$last_tag..$ref"
    if [[ -z "$(git rev-list "$range")" ]]; then
        echo "No commits since $last_tag; nothing to release." >&2
        exit 2
    fi
    subjects="$(git log --format=%s "$range")"
    bodies="$(git log --format=%b "$range")"
    if grep -qE '^[a-z]+(\([^)]*\))?!:' <<<"$subjects" || grep -q 'BREAKING CHANGE' <<<"$bodies"; then
        major=$((major + 1)); minor=0; patch=0
    elif grep -qE '^feat(\([^)]*\))?:' <<<"$subjects"; then
        minor=$((minor + 1)); patch=0
    else
        patch=$((patch + 1))
    fi
    version="$major.$minor.$patch"
fi

IFS=. read -r major minor patch <<<"$version"
if (( minor > 999 || patch > 999 || major > 2000 )); then
    echo "Version $version doesn't fit the versionCode scheme (major*1000000 + minor*1000 + patch)." >&2
    exit 1
fi
code=$((major * 1000000 + minor * 1000 + patch))
# versionCode 1 is what local builds use; a release must always be higher.
if (( code <= 1 )); then
    echo "Version $version gives versionCode $code, which isn't above local builds' 1." >&2
    exit 1
fi

echo "version=$version"
echo "tag=v$version"
echo "code=$code"
