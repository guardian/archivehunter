#!/usr/bin/env python3

from ArchiveHunterHmac import signed_headers
import requests
import argparse

###START MAIN
parser = argparse.ArgumentParser()
parser.add_argument("--id", dest='fileId')
parser.add_argument("--hostname", dest="hostname")
parser.add_argument("--secret", dest="secret")
args = parser.parse_args()

uri = "https://{host}/api/validate/{fileid}".format(host=args.hostname, fileid=args.fileId)
print(uri)

headers = signed_headers(uri, args.secret)
result = requests.get(uri,headers=headers, verify=False)
print(result.status_code)
print(result.text)