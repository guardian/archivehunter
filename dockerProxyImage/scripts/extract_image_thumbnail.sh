#!/bin/bash

#expects arguments:  extract_thumbnail.sh {s3-uri-of-source} {s3-bucket-for-proxies} {http-uri-archivehunter}

echo Shrinking image...
convert /tmp/imagefile -resize 640x360\> /tmp/resized.jpg > /tmp/logfile 2>&1
CONVERT_EXIT=$?
cat /tmp/logfile

if [ "$CONVERT_EXIT" == "0" ]; then
    echo Uploading thumbnail...
    INPATH=$(echo "$1" | sed 's/s3:\/\/[^\/]*\///')
    echo inpath is $INPATH
    INPATH_NO_EXT=$(echo "$INPATH" | sed -E 's/\.[^\.]+$//')
    echo inpath no ext is $INPATH_NO_EXT
    OUTPATH="s3://$2/${INPATH_NO_EXT}_thumb.jpg"
    echo outpath is $OUTPATH

    UPLOAD_LOG=`aws s3 cp /tmp/resized.jpg "$OUTPATH" 2>&1`
    echo Server callback URL is $3

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
    echo Server callback URL is $3
    ENCODED_LOG=$(base64 /tmp/logfile)
    curl -k -X POST $3 -d'{"status":"error","log":"'$ENCODED_LOG'","input":"'"$1"'"}' --header "Content-Type: application/json"
fi