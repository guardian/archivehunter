#!/usr/bin/env python

import csv
import argparse
import logging

logger = logging.getLogger(__name__)
logging.basicConfig(level=logging.DEBUG)

#fix file entries with no extension in the first column of input_list by looking them up in master_list


def find_in_master_list(filename):
    for idx,line in enumerate(master_list_content):
        #logger.debug("{0}, {1}, {2}".format(idx, line, filename))
        if line.startswith(filename):
            return idx
    return None


def strip_path_parts(line, path_parts):
    parts = line.rstrip().split("/")
    return "/".join(parts[path_parts:])


###START MAIN
parser = argparse.ArgumentParser()
parser.add_argument("--input-list", dest='input_list')
parser.add_argument("--output-list", dest='output_list', help="Output to this file, will get over-written")
parser.add_argument("--master-list", dest='master_list', help="Master list to look up files from")
parser.add_argument("--strip-path", dest='path_parts', help="Remove this many path components from master list to get a match")
args = parser.parse_args()

if not args.input_list or not args.output_list or not args.master_list:
    print("Incorrect commandline arguments.  Run with --help to see what you need to specify")
    exit(1)

master_list_content = []

logger.info("Reading {0}...".format(args.master_list))
with open(args.master_list, "r", encoding="latin-1") as fpread:
    master_list_content = list(map(lambda line:strip_path_parts(line, int(args.path_parts)), fpread.readlines()))

logger.info("Done")

logger.info("Scanning {0}...".format(args.input_list))

with open(args.input_list, "r") as fpread:
    reader = csv.reader(fpread)

    with open(args.output_list, "w") as fpout:
        writer = csv.writer(fpout)

        for entry in reader:
            idx = find_in_master_list(entry[0])
            if idx is None:
                logger.error("Could not find {0} in list".format(entry[0]))
                exit(2)
            else:
                entry[0] = master_list_content[idx]
                writer.writerow(entry)
                fpout.flush()
            print(".", end='')

logger.info("Done")