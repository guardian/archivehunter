#!/usr/bin/env python3

import requests
from gnmvidispine.vs_search import VSSearch
from ArchiveHunterHmac import signed_headers
import argparse
import csv
import yaml
import re
import os.path

def get_credentials_from_yaml(filename):
    """
    load the yaml and extract out user, password keys then return as a tuple. raise if it fails
    :param filename:  file to read
    :return:  tuple of username, password
    """
    with open(filename, "r") as f:
        content = yaml.load(f.read())

        return (content["user"], content["password"])


proxy_transform_re = re.compile(r'^/mnt/holdingarea')
def transform_proxy_location(source_loc, actual_holdingpath):
    """
    transforms the location to the actual loaction
    :param source_loc:
    :param actual_holdingpath: holding path to substitute. If not specified, no substitution is done.
    :return:
    """
    if actual_holdingpath is None:
        return source_loc
    else:
        return proxy_transform_re.sub(actual_holdingpath,source_loc)


def list_generator(filename, actual_holdingpath):
    """
    yields tuples of (source_media,proxy_location) from the given list.  transforms proxy_location according to the
    transform_proxy_location method.
    :param filename:
    :return:
    """
    with open(filename,"r") as f:
        reader = csv.reader(f)
        for row in reader:
            transformed_loc = transform_proxy_location(row[1], actual_holdingpath)
            if len(transformed_loc)<2: continue #ignore null entries

            yield (row[0],transformed_loc)


###START MAIN
parser = argparse.ArgumentParser()
parser.add_argument("--list", dest='listfile')
parser.add_argument("--host", dest='host', help="Host that vidispine is running on", default="localhost")
parser.add_argument("--port", dest='port', help="Port for Vidispine", default=8080)
parser.add_argument("--authfile", dest='authfile')
parser.add_argument("--holding-path", dest='holdingpath', help='actual holding path location')
args = parser.parse_args()

if args.holdingpath is not None and not os.path.exists(args.holdingpath):
    raise RuntimeError("Real download path {0} does not exist".format(args.holdingpath))

(user, password) = get_credentials_from_yaml(args.authfile)

n=0
for entry in list_generator(args.listfile, args.holdingpath):
    n+=1
    print("{0}: Proxy location {1}".format(n,entry[1]))
    if not os.path.exists(entry[1]):
        raise RuntimeError("Path does not exist")