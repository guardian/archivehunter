FROM python:3.9-alpine

RUN apk add --no-cache bash fish
RUN adduser -D migration
COPY requirements.txt /tmp
RUN pip3 install -r /tmp/requirements.txt && pip3 install awscli
COPY build-id-list.py /usr/local/bin/build-id-list.py
COPY hmac-client.py /usr/local/bin/hmac-client.py
COPY request-move-file.py /usr/local/bin/request-move-file.py
COPY run-migration.sh /usr/local/bin/run-migration.sh
USER migration
WORKDIR /home/migration
CMD /usr/bin/fish