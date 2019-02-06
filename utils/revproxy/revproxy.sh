#!/usr/bin/env bash

docker run --rm -p 443:443 -v ${PWD}/server.conf:/etc/nginx/conf.d/default.conf:ro -v $PWD/certs:/etc/nginx/certs:ro nginx:alpine