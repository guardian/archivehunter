#!/bin/bash

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

aws s3 cp "$1" /tmp/videofile
MIMETYPE=$(file -b --mime-type /tmp/videofile)
echo $MIMETYPE | grep video

if [ "$?" != "0" ]; then
    echo This is not a video file.
    exit 1
fi

if [ "$FRAME_LOCATION" == "" ]; then
    FRAME_LOCATION=00:01:10
fi

if [ "$OUTPUT_QUALITY" == "" ]; then
    OUTPUT_QUALITY=2
fi

OUTLOG=$(ffmpeg -i -ss ${FRAME_LOCATION} /tmp/videofile -vframes 1 -q:v ${OUTPUT_QUALITY} /tmp/output.jpg 2>&1)

INPATH=$(echo $1 | sed 's/s3:\/\/[^\/]*\///')
echo inpath is $INPATH
INPATH_NO_EXT=$(echo $INPATH | sed -E 's/\.[^\.]+$//')
echo inpath no ext is $INPATH_NO_EXT
OUTPATH=s3://$2/$INPATH_NO_EXT.jpg

if [ "$?" == "0" ]; then
    aws s3 cp /tmp/output.jpg $OUTPATH
    curl -X POST $3 -d'{'"status":"success","output":"$OUTPATH","input":"$1"'}'
else
    ENCODED_LOG=echo $OUTLOG | base64
    curl -X POST $3 -d'{'"status":"error","log":"$ENCODED_LOG"'}'
fi