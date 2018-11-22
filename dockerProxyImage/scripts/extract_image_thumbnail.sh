#!/usr/bin/env bash -e

#expects arguments:  extract_thumbnail.sh {s3-uri-of-source} {s3-bucket-for-proxies} {http-uri-archivehunter}

aws s3 cp "$1" /tmp/imagefile

