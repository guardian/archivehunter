#!/usr/bin/env bash

#expects arguments:  extract_thumbnail.sh {s3-uri-of-source} {s3-bucket-for-proxies} {http-uri-archivehunter}

if [ "$1" == "" ]; then
    echo "You must specific a source S3 URI"
    exit 1
fi

if [ "$2" == "" ]; then
    echo "You must specify a destination bucket"
    exit 1
fi

if [ "$3" == "" ]; then
    echo "You must specify a callback URL"
    exit 1
fi

echo Downloading source media $1...
aws s3 cp "$1" /tmp/mediafile
if [ "$?" != "0" ]; then
    echo Could not download source media.
    exit 1
fi

MIMETYPE=$(file -b --mime-type /tmp/mediafile)

if [[ "$MIMETYPE" =~ ^audio.* ]]; then
    echo Got audio file
    mv /tmp/mediafile /tmp/audiofile
    extract_audio_thumbnail.sh "$1" "$2" "$3"
    exit $?
elif [[ "$MIMETYPE" =~ ^video.* ]]; then
    echo Got video file
    mv /tmp/mediafile /tmp/videofile
    extract_video_thumbnail.sh "$1" "$2" "$3"
    exit $?
elif [[ "$MIMETYPE" =~ ^image.* ]]; then
    echo Got image file
    mv /tmp/mediafile /tmp/imagefile
    extract_image_thumbnail.sh "$1" "$2" "$3"
    exit $?
else
    echo ${MIMETYPE} files are not supported yet
    exit 1
fi