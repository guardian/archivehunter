#!/usr/bin/env bash


DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )/.." >/dev/null 2>&1 && pwd )"

if [ ! -d "$DIR/esdata" ]; then
    echo Elasticsearch data directory "$DIR/esdata" does not exist. Creating from fresh...
    mkdir -p "$DIR/esdata"
fi

docker run -v "$DIR/esdata":/usr/share/elasticsearch/data -p 9200:9200 -e "ES_JAVA_OPTS=-Xms1g -Xmx1g" docker.elastic.co/elasticsearch/elasticsearch:6.3.2
