#!/usr/bin/env python3

import requests
from gnmvidispine.vs_search import VSSearch
from ArchiveHunterHmac import signed_headers
import argparse
import csv
import yaml
import re
import os.path


def does_filepath_match(item, filepath):
    item_filepath = os.path.dirname(item.get("gnm_asset_filename"))
    item_filename = os.path.basename(item.get("gnm_asset_filename"))

    if item_filepath == "":
        return False

    if item_filename != os.path.basename(filepath):
        return False

    if os.path.dirname(filepath).endswith(item_filepath):
        return True

    return False


def get_search(host,port,user,passwd):
    """
    stub function for getting a new vssearch; this is to make test mocking easier
    :param host:
    :param port:
    :param user:
    :param passwd:
    :return:
    """
    return VSSearch(host=host,port=port,user=user, passwd=passwd)


def find_in_vidispine(filename, user, passwd):
    """
    try to find the given filename in vidispine, using the gnm_asset_filename field
    :param filename:
    :param user:
    :param passwd:
    :return:
    """
    basename = os.path.basename(filename)
    interesting_fields = ["itemId","title","gnm_asset_filename","gnm_asset_category","gnm_external_archive_external_archive_status","gnm_external_archive_external_archive_request","gnm_external_archive_external_archive_path","gnm_external_archive_committed_to_archive_at"]

    s = get_search(args.host, args.port, user, passwd)

    s.addCriterion({"gnm_asset_filename": '"' + basename + '"'})
    result = s.execute()

    print("\tFound {0} items for {1}:".format(result.totalItems, filename))

    if result.totalItems>100:
        print("\tToo many items to search, leaving it")
        return None

    item_list = []
    for item in result.results(shouldPopulate=False):
        item.populate(item.name, specificFields=interesting_fields)
        for field in interesting_fields:
            print("\t\t{0}: {1}".format(field, item.get(field)))
        if does_filepath_match(item, filename):
            item_list.append(item)
        print("--------")

    if result.totalItems==1:
        return item_list[0]
    elif result.totalItems==0:
        return None
    else:
        if len(item_list)==1:
            return item_list[0]
        else:
            print("Warning, {0} items still matched filter; not returning anything".format(len(item_list)))
            return None


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
            transformed_loc = transform_proxy_location(row[2], actual_holdingpath)
            if len(transformed_loc)<2: continue #ignore null entries

            yield (row[0],transformed_loc)

#for compatibility with tests
class DummyArgs(object):
    def __init__(self):
        self.host=""
        self.port=-1

args = DummyArgs()

###START MAIN
if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--list", dest='listfile')
    parser.add_argument("--host", dest='host', help="Host that vidispine is running on", default="localhost")
    parser.add_argument("--port", dest='port', help="Port for Vidispine", default=8080)
    parser.add_argument("--authfile", dest='authfile')
    parser.add_argument("--holding-path", dest='holdingpath', help='actual holding path location')
    parser.add_argument("--ignore", dest='ignore', help='continue even if proxy files don\'t exist', action='store_true')
    parser.add_argument("--output-file", dest='output', help='write list to this file', default='in_vidispine.csv')
    args = parser.parse_args()

    if args.holdingpath is not None and not os.path.exists(args.holdingpath):
        raise RuntimeError("Real download path {0} does not exist".format(args.holdingpath))

    (user, password) = get_credentials_from_yaml(args.authfile)

    with open(args.output, "w") as fpout:
        csvwriter = csv.writer(fpout)
        n=0

        for entry in list_generator(args.listfile, args.holdingpath):
            n+=1
            print("{0}: Proxy location {1}".format(n, entry[1]))
            if not args.ignore and not os.path.exists(entry[1]):
                raise RuntimeError("Path does not exist")
            item = find_in_vidispine(entry[0], user, password)
            if item:
                csvwriter.writerow([entry[0],entry[1],item.name, item.get("gnm_external_archive_external_archive_status"), item.get("gnm_external_archive_external_archive_path")])
            else:
                csvwriter.writerow([entry[0], entry[1]])
            fpout.flush()
