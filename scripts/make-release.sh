#!/usr/bin/env bash
# Cut a TVTron release. Bumps versionName/versionCode, builds, tags, pushes,
# and creates a GitHub Release with the APK attached. The in-app updater polls
# `releases/latest` so a successful run rolls out OTA to all sideload installs.
#
# Usage:
#   scripts/make-release.sh 0.1.1
#   scripts/make-release.sh 0.1.1 --notes "Fixed channel zap on first launch"
#   scripts/make-release.sh 0.1.1 --notes-file CHANGELOG.md
#   scripts/make-release.sh 0.1.1 --prerelease

set -euo pipefail

REPO="alkevintan/tvtron"
GRADLE_FILE="app/build.gradle"
APK_SRC="app/build/outputs/apk/debug/app-debug.apk"

die() { echo "error: $*" >&2; exit 1; }

[[ $# -ge 1 ]] || die "usage: $0 <version> [--notes \"...\" | --notes-file path] [--prerelease]"
VERSION="$1"; shift
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "version must be MAJOR.MINOR.PATCH (got: $VERSION)"
TAG="v$VERSION"

NOTES=""
NOTES_FILE=""
PRERELEASE=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --notes) NOTES="$2"; shift 2 ;;
        --notes-file) NOTES_FILE="$2"; shift 2 ;;
        --prerelease) PRERELEASE="--prerelease"; shift ;;
        *) die "unknown flag: $1" ;;
    esac
done

cd "$(dirname "$0")/.."

command -v gh >/dev/null || die "gh CLI not installed"
gh auth status >/dev/null 2>&1 || die "gh not authenticated — run: gh auth login"
[[ -f "$GRADLE_FILE" ]] || die "must run from repo root (missing $GRADLE_FILE)"

BRANCH="$(git rev-parse --abbrev-ref HEAD)"
[[ "$BRANCH" == "main" ]] || die "not on main branch (on: $BRANCH)"

if [[ -n "$(git status --porcelain)" ]]; then
    die "working tree dirty — commit or stash first"
fi

if git rev-parse --verify "refs/tags/$TAG" >/dev/null 2>&1; then
    die "tag $TAG already exists locally"
fi
if gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1; then
    die "release $TAG already exists on GitHub"
fi

git fetch origin --quiet
LOCAL=$(git rev-parse @)
REMOTE=$(git rev-parse @{u} 2>/dev/null || echo "")
[[ -z "$REMOTE" || "$LOCAL" == "$REMOTE" ]] || die "local main is not in sync with origin/main"

if [[ -z "${JAVA_HOME:-}" ]]; then
    if [[ -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]]; then
        export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
    fi
fi
[[ -n "${JAVA_HOME:-}" ]] || die "JAVA_HOME not set and Android Studio JBR not found"

CURRENT_VERSION_NAME="$(grep -E '^\s*versionName ' "$GRADLE_FILE" | sed -E 's/.*versionName "([^"]+)".*/\1/')"
CURRENT_VERSION_CODE="$(grep -E '^\s*versionCode ' "$GRADLE_FILE" | awk '{print $2}')"
[[ -n "$CURRENT_VERSION_NAME" ]] || die "could not read versionName from $GRADLE_FILE"
[[ -n "$CURRENT_VERSION_CODE" ]] || die "could not read versionCode from $GRADLE_FILE"
NEW_VERSION_CODE=$((CURRENT_VERSION_CODE + 1))

echo "→ versionName: $CURRENT_VERSION_NAME → $VERSION"
echo "→ versionCode: $CURRENT_VERSION_CODE → $NEW_VERSION_CODE"

if sed --version >/dev/null 2>&1; then
    SED_INPLACE=(sed -i)
else
    SED_INPLACE=(sed -i '')
fi
"${SED_INPLACE[@]}" -E "s/^([[:space:]]*versionName )\".*\"/\1\"$VERSION\"/" "$GRADLE_FILE"
"${SED_INPLACE[@]}" -E "s/^([[:space:]]*versionCode )[0-9]+/\1$NEW_VERSION_CODE/" "$GRADLE_FILE"

echo "→ building debug APK"
./gradlew :app:assembleDebug -q
[[ -f "$APK_SRC" ]] || die "build did not produce $APK_SRC"
APK_DST="/tmp/TVTron-$VERSION.apk"
cp "$APK_SRC" "$APK_DST"
APK_BYTES=$(wc -c < "$APK_DST")
echo "→ APK ready: $APK_DST ($((APK_BYTES / 1024 / 1024)) MB)"

if [[ -n "$NOTES_FILE" ]]; then
    [[ -f "$NOTES_FILE" ]] || die "notes file not found: $NOTES_FILE"
    NOTES="$(cat "$NOTES_FILE")"
elif [[ -z "$NOTES" ]]; then
    PREV_TAG="$(git describe --tags --abbrev=0 2>/dev/null || true)"
    if [[ -n "$PREV_TAG" ]]; then
        echo "→ generating notes from commits since $PREV_TAG"
        NOTES="$(git log --pretty='- %s' "$PREV_TAG"..HEAD)"
    else
        NOTES="Release $TAG"
    fi
fi

git add "$GRADLE_FILE"
git commit -m "chore: release $TAG" >/dev/null
git tag -a "$TAG" -m "$TAG"

echo "→ pushing main + tag"
git push origin main --quiet
git push origin "$TAG" --quiet

echo "→ creating GitHub release"
gh release create "$TAG" "$APK_DST" \
    --repo "$REPO" \
    --title "$TAG" \
    --notes "$NOTES" \
    $PRERELEASE \
    >/dev/null

echo
echo "✓ Released $TAG"
echo "  https://github.com/$REPO/releases/tag/$TAG"
echo
echo "Sideloaded apps will see this update within 24h via auto-check,"
echo "or immediately via Settings → Check for updates."
