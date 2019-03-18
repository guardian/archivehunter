FROM python:3.7-alpine

RUN mkdir /usr/local/scripts
#install requirements first, to avoid always duplicating the layer that contains them. scripts are the bits
#that usually change so they are at the end.
COPY requirements.txt /usr/local/scripts
RUN pip install -r /usr/local/scripts/requirements.txt
COPY * /usr/local/scripts/
RUN chmod a+r /usr/local/scripts/*
USER daemon
