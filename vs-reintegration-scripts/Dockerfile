FROM python:3.7-alpine

RUN mkdir /usr/local/scripts; apk add git
#install requirements first, to avoid always duplicating the layer that contains them. scripts are the bits
#that usually change so they are at the end.
COPY requirements.txt /usr/local/scripts
RUN pip install -r /usr/local/scripts/requirements.txt
RUN cd /tmp && git clone https://github.com/fredex42/gnmvidispine.git && cd gnmvidispine && git checkout python3 && git log | head -n40 && pip install -r requirements.txt && python ./setup.py install && cd /tmp && rm -rf /tmp/gnmvidispine
COPY download-proxies/*.py /usr/local/scripts/
COPY reintegrate-from-list/*.py /usr/local/scripts/
COPY create-missing-from-list/*.py /usr/local/scripts/
COPY tidy-imported/*.py /usr/local/scripts/
COPY split-out-list/*.py /usr/local/scripts/

#COPY certs/* /etc/ssl/certs/
RUN chmod a+r /usr/local/scripts/*
#USER daemon
