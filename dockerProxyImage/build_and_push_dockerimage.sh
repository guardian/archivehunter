#!/usr/bin/env bash

cd "${BASH_SOURCE%/*}/" || exit                            # cd into the bundle and use relative paths
docker build . -t guardianmultimedia/archivehunter-proxyimage:$BUILD_NUM
docker push guardianmultimedia/archivehunter-proxyimage:$BUILD_NUM