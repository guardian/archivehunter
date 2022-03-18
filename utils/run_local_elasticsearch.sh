#!/usr/bin/env bash


DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )/.." >/dev/null 2>&1 && pwd )"

docker network ls | grep archivehunter-dev >/dev/null 2>&1

if [ "$?" != "0" ]; then
    echo Setting up docker network....
    docker network create archivehunter-dev
fi

docker volume ls | grep archivehunter-dev >/dev/null 2>&1

if [ "$?" != "0" ]; then
  echo Setting up docker volume...
  docker volume create --driver local --opt type=tmpfs --opt device=tmpfs --opt o=size=200m archivehunter-dev
fi

#if [ ! -d "$DIR/esdata" ]; then
#    echo Elasticsearch data directory "$DIR/esdata" does not exist. Creating from fresh...
#    mkdir -p "$DIR/esdata"
#fi

docker run -v archivehunter-dev:/usr/share/elasticsearch/data --name elasticsearch --network=archivehunter-dev -p 9200:9200 -e "ES_JAVA_OPTS=-Xms1g -Xmx1g" docker.elastic.co/elasticsearch/elasticsearch:6.3.2
