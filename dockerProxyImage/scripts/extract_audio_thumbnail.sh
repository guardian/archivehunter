#!/usr/bin/env bash

echo extract_audio_thumbnail starting. Arguments: $1 $2 $3


echo Converting audio...
ffmpeg -y -i /tmp/audiofile -vn -acodec pcm_s16le -r 8k -ac 1 -f wav /tmp/temp1.wav > /tmp/logfile 2>&1
FFMPEG_EXIT=$?

SOX_EXIT=-1
if [ "$FFMPEG_EXIT" == "0" ]; then
    echo Extracting waveform data...
    sox /tmp/temp1.wav -t dat - | tail -n+3 > /tmp/audio_only.dat 2>/tmp/logfile
    SOX_EXIT=$?
fi

GNUPLOT_EXIT=-1
if [ "$SOX_EXIT" == "0" ]; then
    echo Plotting waveform...
    gnuplot /usr/local/share/thumbnailer/audio.gpi > /tmp/logfile 2>&1
    GNUPLOT_EXIT=$?
fi


if [ "$FFMPEG_EXIT" == "0" ] && [ "$SOX_EXIT" == "0" ] && [ "$GNUPLOT_EXIT" == "0" ]; then
    echo Uploading thumbnail...
    INPATH=$(echo "$1" | sed 's/s3:\/\/[^\/]*\///')
    echo inpath is $INPATH
    INPATH_NO_EXT=$(echo "$INPATH" | sed -E 's/\.[^\.]+$//')
    echo inpath no ext is $INPATH_NO_EXT
    OUTPATH="s3://$2/$INPATH_NO_EXT.png"
    echo outpath is $OUTPATH

    UPLOAD_LOG=`aws s3 cp /tmp/audio.png "$OUTPATH" 2>&1`
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