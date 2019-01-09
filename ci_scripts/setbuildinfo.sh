#!/usr/bin/env bash

#This script is run during the CI process to overwrite the version.properties file in conf/ with information about
#the current build.  this is then read by a server-side controller, and shown to the user via the 'About' panel.
declare -x BUILD_TIME=$(date --utc +%FT%TZ)

cat > conf/version.properties <<EOF
buildBranch=${BUILD_BRANCH}
buildNumber=${BUILD_NUM}
buildCommit=${BUILD_COMMIT}
buildDate=${BUILD_TIME}
EOF