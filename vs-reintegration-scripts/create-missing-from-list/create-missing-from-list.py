#!/usr/bin/env python

import csv
import argparse
from locate_project import ProjectLocator
from setup_media_item import setup_item
from validate_media import find_s3_media
import dateutil.parser
import yaml
import logging
import os.path

logger = logging.getLogger(__name__)
temp = logging.getLogger("urllib3")
temp.setLevel(logging.WARN)
temp = logging.getLogger("botocore")
temp.setLevel(logging.WARN)
temp = logging.getLogger("gnmvidispine.vidispine_api")
temp.setLevel(logging.WARN)
logging.basicConfig(level=logging.DEBUG, format="%(asctime)s %(name)s [%(levelname)s] %(funcName)s - %(message)s")


def get_credentials_from_yaml(filename):
    """
    load the yaml and extract out user, password keys then return as a tuple. raise if it fails
    :param filename:  file to read
    :return:  tuple of username, password
    """
    with open(filename, "r") as f:
        content = yaml.load(f.read())
        return (content["user"], content["password"])


def convert_path(path):
    return path


###START MAIN
parser = argparse.ArgumentParser()
parser.add_argument("--list", dest='listfile')
parser.add_argument("--host", dest='host', help="Host that pluto is running on", default="localhost")
parser.add_argument("--port", dest='port', help="Port for pluto", default=443)
parser.add_argument("--authfile", dest='authfile')
parser.add_argument("--archivehunter-collection", dest='ah_collection', help="Collection name in ArchiveHunter that these items belong to")
parser.add_argument("--asset-folder-root", dest='assetroot', help="Base path of asset folders for pluto", default="/srv/media")
parser.add_argument("--insecure", dest='insecure', help='use http instead of https', action='store_true')
parser.add_argument("--output-file", dest='output', help='write list to this file', default='in_vidispine.csv')
args = parser.parse_args()

logger.info("test")
(user, password) = get_credentials_from_yaml(args.authfile)
locator = ProjectLocator(host=args.host, port=args.port, user=user, passwd=password)

if args.ah_collection is None or args.ah_collection == "":
    print("You must specify --archivehunter-collection")
    exit(2)

with open(args.listfile, "r") as f:
    reader = csv.reader(f)

    for raw_row in reader:
        try:
            entry = {
                'path': raw_row[0],
                'proxy_path': raw_row[1],
                'vsid': raw_row[2] if len(raw_row)>2 else None
            }

            path_components = entry["path"].split("/")
            asset_folder_path = args.assetroot + "/".join(path_components[0:3])
            logger.debug("asset folder path for {0} is {1}".format(entry["path"], asset_folder_path))
            if entry["vsid"] is None or entry["vsid"]=="":
                project_id = locator.lookup_path(asset_folder_path)
                logger.debug("Got project ID {0}".format(project_id))

                full_media_path = os.path.join(args.assetroot, entry["path"])

                s3meta = find_s3_media(args.ah_collection, entry["path"])

                logger.info("Found in s3: {0}".format(s3meta))
                item = setup_item(host=args.host, user=user, passwd=password, full_filepath=full_media_path,
                           proxy_filepath=convert_path(entry["proxy_path"]),
                           collection_name=args.ah_collection,
                           archive_path=entry["path"],
                           archive_timestamp=s3meta["timestamp"],   #this is already a datetime object
                           parent_project_id=project_id,
                           wait=True)

                logger.info("Set up item {0}".format(item.name))
        except Exception as e:
            logger.error("Item was not set up: {0}".format(str(e)))
