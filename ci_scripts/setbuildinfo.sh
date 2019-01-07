#!/usr/bin/env bash

declare -x BUILD_TIME=$(date --utc +%FT%TZ)

cat > conf/version.properties <<EOF
buildBranch=${BUILD_BRANCH}
buildNumber=${BUILD_NUM}
buildCommit=${BUILD_COMMIT}
buildDate=${BUILD_TIME}
EOF