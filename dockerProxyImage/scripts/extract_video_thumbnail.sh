#!/bin/bash

#expects arguments:  extract_thumbnail.sh {s3-uri-of-source} {s3-bucket-for-proxies} {http-uri-archivehunter}
echo extract_video_thumbnail starting. Arguments: $1 $2 $3

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
aws s3 cp "$1" /tmp/videofile
if [ "$?" != "0" ]; then
    echo Could not download source media.
    exit 1
fi

MIMETYPE=$(file -b --mime-type /tmp/videofile)
echo $MIMETYPE | grep video

if [ "$?" != "0" ]; then
    echo This is not a video file.
    exit 1
fi

if [ "$FRAME_LOCATION" == "" ]; then
    FRAME_LOCATION=00:00:10
fi

if [ "$OUTPUT_QUALITY" == "" ]; then
    OUTPUT_QUALITY=2
fi

echo Extracting thumbnail...
echo ffmpeg -i /tmp/videofile -ss ${FRAME_LOCATION} -vframes 1 -q:v ${OUTPUT_QUALITY} /tmp/output.jpg


OUTLOG=$(ffmpeg -i /tmp/videofile -ss ${FRAME_LOCATION} -vframes 1 -q:v ${OUTPUT_QUALITY} -y /tmp/output.jpg 2>&1)

FFMPEG_EXIT=$?
echo $OUTLOG

INPATH=$(echo "$1" | sed 's/s3:\/\/[^\/]*\///')
echo inpath is $INPATH
INPATH_NO_EXT=$(echo "$INPATH" | sed -E 's/\.[^\.]+$//')
echo inpath no ext is $INPATH_NO_EXT
OUTPATH="s3://$2/$INPATH_NO_EXT.jpg"
echo outpath is $OUTPATH

if [ "$FFMPEG_EXIT" == "0" ]; then
    echo Uploading thumbnail...
    UPLOAD_LOG=`aws s3 cp /tmp/output.jpg "$OUTPATH" 2>&1`
    if [ "$?" == "0" ]; then
        echo Informing server...
        curl -k -X POST $3 -d'{"status":"success","output":"'"$OUTPATH"'","input":"'"$1"'"}' --header "Content-Type: application/json"
    else
        echo Informing server of failure...
        ENCODED_LOG=$(echo $UPLOAD_LOG | base64)

        curl -k -X POST $3 -d'{"status":"error","log":"'$ENCODED_LOG'","input":"'"$1"'"}' --header "Content-Type: application/json"
    fi
else
    echo Output failed. Informing server...
    ENCODED_LOG=$(echo $OUTLOG | base64)
    curl -k -X POST $3 -d'{"status":"error","log":"'$ENCODED_LOG'","input":"'"$1"'"}' --header "Content-Type: application/json"
fi