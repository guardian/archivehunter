#!/usr/bin/env python3

import argparse
import requests
import base64
from os.path import basename, dirname
import os.path
import re
from ArchiveHunterHmac import signed_headers

is_dotfile = re.compile(r'^\.')
extract_xtn = re.compile(r'^.*\.([^.]+)$')

expect_video_proxy = ["avi","mxf","mp4","mov","lrv"]
expect_image_proxy = ["jpg","cr2","tif","tiff","tga"]
expect_audio_proxy = ["wav","aif","aiff","mp3","m4a"]

ignore_regex_source_list = [r"^Rendered - .*"]
ignore_regex_list = list(map(lambda src: re.compile(src), ignore_regex_source_list))


def should_ignore(filename):
    for expr in ignore_regex_list:
        if expr.search(filename):
            return True

    return False


def each_filepath(listfile, strip_parts):
    """
    yields each line of the provided file in a block, provided it's not a dotfile
    :param listfile: filepath to open
    :return:
    """
    with open(listfile, "r", encoding="iso-8859-1") as f:
        for line in f.readlines():
            filepath = line.rstrip().encode("iso-8859-1").decode("UTF-8")   #convert the line into unicode
            filename_only = basename(filepath)

            if not is_dotfile.match(filename_only) and not should_ignore(filename_only):
                if strip_parts:
                    path_parts = dirname(filepath).split("/")
                    new_filepath = os.path.join("/".join(path_parts[int(strip_parts):]), filename_only)
                    yield new_filepath
                else:
                    yield filepath


def file_extension(filename):
    """
    get the file extension for the given file, if any
    :param filename: filename
    :return: file extension (after dot), or None
    """
    parts = extract_xtn.match(filename)
    if parts:
        return parts.group(1).lower()
    else:
        return None


def fileid_for(collection, filepath):
    """
    generates the archive hunter file ID for the given collection and filepath
    :param collection:
    :param filepath:
    :return:
    """
    return base64.b64encode(bytes("{0}:{1}".format(collection, filepath),"UTF-8")).decode("UTF-8")


def verify_item(archive_hunter_id):
    uri = "https://{host}/api/validate/{fileid}".format(host=args.hostname, fileid=archive_hunter_id)
    headers = signed_headers(uri, args.secret)
    response = requests.get(uri, headers=headers)
    # print(response.status_code)
    # print(response.text)
    if response.status_code==404:
        return False
    elif response.status_code==200:
        return True
    else:
        print(response.text)
        raise Exception("Server returned {0}".format(response.status_code))


def proxies_for(archive_hunter_id):
    uri = "https://{host}/api/proxy/{fileid}/all".format(host=args.hostname, fileid=archive_hunter_id)
    headers = signed_headers(uri, args.secret)

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
parser.add_argument("--strip", dest="strip")
args = parser.parse_args()

if not args.collection or not args.listfile or not args.hostname or not args.secret:
    print("You have not specified enough arguments. Run with --help to see details")
    exit(2)

print("Scanning for files...")
n=0
seen_extensions = []

for filepath in each_filepath(args.listfile, args.strip):
    #print(filepath)
    n+=1
    print("{0}".format(n), end="\r")
    archive_hunter_id = fileid_for(args.collection, filepath)
    filename_only = basename(filepath)
    extension = file_extension(filename_only)
    if not extension in seen_extensions:
        seen_extensions.append(extension)

    if verify_item(archive_hunter_id):
        print("{0}: Item verified as existing".format(filename_only))
        if extension in expect_video_proxy or extension in expect_audio_proxy or extension in expect_image_proxy:
            proxies = proxies_for(archive_hunter_id)
            print(proxies)

print("Seen extensions: {0}".format(seen_extensions))