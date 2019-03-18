FROM python:3.7-alpine

RUN mkdir /usr/local/scripts
COPY * /usr/local/scripts/
RUN chmod a+r /usr/local/scripts/*
RUN pip install -r /usr/local/scripts/requirements.txt
USER daemon
