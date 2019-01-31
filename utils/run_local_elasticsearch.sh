#!/usr/bin/env bash


DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )/.." >/dev/null 2>&1 && pwd )"

if [ ! -d "$DIR/esdata" ]; then
    echo Elasticsearch data directory "$DIR/esdata" does not exist. Creating from fresh...
    mkdir -p "$DIR/esdata"
fi

docker run --rm -v "$DIR/esdata":/usr/share/elasticsearch/data -p 9200:9200 docker.elastic.co/elasticsearch/elasticsearch:6.3.2
