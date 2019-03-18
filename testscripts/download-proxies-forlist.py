#!/usr/bin/env python3

import argparse
import requests
import base64
from os.path import basename, dirname
import re
from ArchiveHunterHmac import signed_headers

is_dotfile = re.compile('^\.')


def each_filepath(listfile):
    """
    yields each line of the provided file in a block, provided it's not a dotfile
    :param listfile: filepath to open
    :return:
    """
    with open(listfile, "r") as f:
        for line in f.readlines():
            filepath = line.rstrip()
            filename_only = basename(filepath)
            if not is_dotfile.match(filename_only):
                yield filepath


def proxies_for(collection, filepath):
    archive_hunter_id = base64.b64encode(bytes("{0}:{1}".format(collection, filepath),"UTF-8")).decode("UTF-8")
    uri = "https://{host}/api/proxy/{fileid}/all".format(host=args.hostname, fileid=archive_hunter_id)
    print(uri)
    headers = signed_headers(uri, args.secret)
    print(headers)

    response = requests.get(uri, headers=headers)
    if response.status_code!=200:
        print(response.text)
        raise Exception("Server returned {0}".format(response.status_code))
    else:
        return response.json()


###START MAIN
parser = argparse.ArgumentParser()
parser.add_argument("--list", dest='listfile')
parser.add_argument("--collection", dest="collection")
parser.add_argument("--hostname", dest="hostname")
parser.add_argument("--secret", dest="secret")
args = parser.parse_args()

if not args.collection or not args.listfile or not args.hostname or not args.secret:
    print("You have not specified enough arguments. Run with --help to see details")
    exit(2)

for filepath in each_filepath(args.listfile):
    print(filepath)
    proxies = proxies_for(args.collection, filepath)
    print(proxies)