#!/usr/bin/env python3

import argparse
import requests
import base64
from os.path import basename, dirname
import os.path
import re
from ArchiveHunterHmac import signed_headers
from functools import cmp_to_key
import locale
import json
import logging
from ProxyDownloader import ProxyDownloader
from queue import Queue

LOGFORMAT = '%(asctime)-15s - %(levelname)s - %(funcName)s: %(message)s'

logging.basicConfig(level=logging.ERROR, format=LOGFORMAT)

logger = logging.getLogger("main")
logger.level = logging.INFO

is_dotfile = re.compile(r'^\.')
extract_xtn = re.compile(r'^.*\.([^.]+)$')

expect_video_proxy = ["avi","mxf","mp4","mov","lrv","dv","m1v","m2v","m4v","mkv","mpeg","mpg","r3d","webm","webp","wmv"]
expect_image_proxy = ["jpg","cr2","tif","tiff","tga", "thm","bmp","dng","dpx","drx","exr","flv","h264","jp2","jpe",
                      "jpeg","nef","psd","png","tif"]
expect_audio_proxy = ["aac","ac3","aifc","wav","aif","aiff","mp3","m4a","caf", "fla","flac","mp2","ogg","wma"]

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
            filepath = line.rstrip().encode("iso-8859-1").decode("UTF-8", errors="ignore")   #convert the line into unicode
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
    logger.debug(response.status_code)
    logger.debug(response.text)
    if response.status_code==404:
        return False
    elif response.status_code==200:
        return True
    else:
        logger.error(response.text)
        raise Exception("Server returned {0}".format(response.status_code))


def proxies_for(archive_hunter_id):
    uri = "https://{host}/api/proxy/{fileid}/all".format(host=args.hostname, fileid=archive_hunter_id)
    headers = signed_headers(uri, args.secret)

    response = requests.get(uri, headers=headers)
    if response.status_code!=200:
        logger.error(response.text)
        raise Exception("Server returned {0}".format(response.status_code))
    else:
        try:
            return response.json()
        except json.JSONDecodeError as e:
            logger.error("Server responded {0}".format(response.text))
            logger.exception("Could not decode server response")
            return {"entries": []}


def request_generate_proxy(archive_hunter_id, proxyType):
    uri = "https://{host}/api/proxy/generate/{id}/{type}".format(host=args.hostname, id=archive_hunter_id, type=proxyType)
    headers = signed_headers(uri, args.secret)

    response = requests.post(uri,headers=headers)
    if response.status_code!=200:
        logger.error(response.text)
        raise Exception("Server returned {0}".format(response.status_code))
    else:
        logger.info("{0} proxy requested for {1}".format(proxyType, archive_hunter_id))


def which_proxy_for(file_extension):
    if file_extension in expect_video_proxy:
        return ["VIDEO","THUMBNAIL"]
    elif file_extension in expect_audio_proxy:
        return ["AUDIO","THUMBNAIL"]
    elif file_extension in expect_image_proxy:
        return ["THUMBNAIL"]
    else:
        return []

def request_proxy_for(entry):
    """
    enqueues a download request
    :param entry:
    :return:
    """
    rq = {
        "path": os.path.dirname(entry["bucketPath"]),
        "name": os.path.basename(entry["bucketPath"]),
        "archive_hunter_id": entry["fileId"]
    }
    download_queue.put(rq)

###START MAIN
parser = argparse.ArgumentParser()
parser.add_argument("--list", dest='listfile')
parser.add_argument("--collection", dest="collection")
parser.add_argument("--hostname", dest="hostname")
parser.add_argument("--secret", dest="secret")
parser.add_argument("--strip", dest="strip")
parser.add_argument("--test", dest="test", action="store_true")
parser.add_argument("--download-threads", dest="threads", default=5)
args = parser.parse_args()

if not args.collection or not args.listfile or not args.hostname or not args.secret:
    print("You have not specified enough arguments. Run with --help to see details")
    exit(2)

logger.info("Scanning for files...")
n=0
seen_extensions = []

download_queue = Queue()
download_thread_list = []
for n in range(0, args.threads):
    t = ProxyDownloader(queue=download_queue, hostname=args.hostname, secret=args.secret)
    t.start()
    download_thread_list.append(t)

for filepath in each_filepath(args.listfile, args.strip):
    logger.debug(filepath)
    n+=1

    print("{0}".format(n), end="\r")
    archive_hunter_id = fileid_for(args.collection, filepath)

    filename_only = basename(filepath)
    extension = file_extension(filename_only)
    if not extension in seen_extensions:
        seen_extensions.append(extension)

    if args.test:
        continue

    if verify_item(archive_hunter_id):
        logger.debug("{0}: Item verified as existing".format(filename_only))
        if extension in expect_video_proxy or extension in expect_audio_proxy or extension in expect_image_proxy:
            proxies = proxies_for(archive_hunter_id)

            if(len(proxies["entries"])==0):
                for proxy_type in which_proxy_for(extension):
                    logger.info("{0}: Item needs proxy {1}".format(filename_only, proxy_type))
                    try:
                        request_generate_proxy(archive_hunter_id, proxy_type)
                    except Exception as e:
                        logger.exception("Could not request proxy")
            else:
                logger.info("Got proxies: {0}".format(proxies["entries"]))
                for entry in proxies["entries"]:
                    request_proxy_for(entry)

sorted_extns = sorted(filter(lambda x: x is not None, seen_extensions), key=cmp_to_key(locale.strcoll))

logger.info("Seen extensions: {0}".format(sorted_extns))

logger.info("Shutting down threads...")
for n in range(0, args.threads):
    download_queue.put(None)

for t in download_thread_list:
    t.join()

logger.info("Done")