#!/usr/bin/env bash

cd "${BASH_SOURCE%/*}/" || exit                            # cd into the bundle and use relative paths

#need to setup up AWS credentials in the build config
declare -x LOGIN_CMD=$(aws ecr get-login --no-include-email --region eu-west-1)
${LOGIN_CMD}

if [ "${DOCKER_PATH}" == "" ]; then
    declare -x DOCKER_PATH=guardianmultimedia/archivehunter-proxyimage
fi

docker build . -t ${DOCKER_PATH}:$BUILD_NUM
docker push ${DOCKER_PATH}:$BUILD_NUM