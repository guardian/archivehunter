#!/bin/bash

if [ "${HOST}" == "" ]; then
  echo You must specify HOST in the environment
  exit 2
fi

if [ "${SECRET}" == "" ]; then
  echo You must specify SECRET in the environment
  exit 2
fi

if [ "${DEST}" == "" ]; then
  echo You must specify DEST \(the destination bucket\) in the environment
  exit 2
fi

if [ "${BUCKET}" == "" ]; then
  echo You must specify BUCKET in the environment
  exit 2
fi

if [ "${DATADIR}" == "" ]; then
  echo No DATADIR specified, using /tmp
  export DATADIR=/tmp
else
  echo DATADIR is $DATADIR
fi

if [ "${DELAY}" == "" ]; then
  echo No DELAY specified, using 2s
  export DELAY="2s"
else
  echo DELAY is $DELAY
fi

echo ------------------------------------------------------
echo Migration run starting up at $(date "+%Y-%m-%d %H:%M:%S")
echo ------------------------------------------------------

echo Listing ${BUCKET}...
python3 /usr/local/bin/build-id-list.py --bucket "${BUCKET}" > "$DATADIR/${BUCKET}.txt"
if [ "$?" != "0" ]; then
  echo Bucket list failed with error $?
  exit 1
fi

FILECOUNT=$(wc -l "$DATADIR/${BUCKET}.txt")
echo ${BUCKET} has ${FILECOUNT} items to migrate

IFS='
'

for entry in `cat "$DATADIR/${BUCKET}.txt"`; do
  python3 /usr/local/bin/request-move-file.py --host="${HOST}" --secret="${SECRET}" --dest="${DEST}" --id="${entry}"
  if [ "$?" != "0" ]; then
    echo request-move-file failed with error $?
    exit 1
  fi
  sleep "${DELAY}"
done

echo ------------------------------------------------------
echo Migration run completed at $(date "+%Y-%m-%d %H:%M:%S")
echo ------------------------------------------------------