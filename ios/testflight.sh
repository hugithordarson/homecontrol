#!/bin/zsh
# Archives Ljós and uploads it to App Store Connect, where it appears in TestFlight
# for the internal testers once processing is done (usually 5–15 minutes).
# Uses the Apple account signed in to Xcode (Settings → Accounts).
set -euo pipefail

cd "$(dirname "$0")"

# Every upload needs a build number higher than the last; a timestamp always is
BUILD=$(date +%Y%m%d%H%M)

xcodegen generate --quiet
rm -rf build/Ljos.xcarchive

echo "== Archiving build $BUILD"
xcodebuild -project Ljos.xcodeproj -scheme Ljos -configuration Release \
	-destination 'generic/platform=iOS' -archivePath build/Ljos.xcarchive \
	-allowProvisioningUpdates CURRENT_PROJECT_VERSION=$BUILD \
	archive -quiet

echo "== Uploading to App Store Connect"
xcodebuild -exportArchive -archivePath build/Ljos.xcarchive \
	-exportOptionsPlist ExportOptions.plist -exportPath build/export \
	-allowProvisioningUpdates -quiet

echo "Uploaded build $BUILD — it shows up in TestFlight after processing"
