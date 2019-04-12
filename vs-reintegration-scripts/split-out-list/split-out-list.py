#!/usr/bin/env python3

## Simple script to split a list file out into a number of equally-sized parts
import argparse
import os.path
import logging
import re

logging.basicConfig(level=logging.DEBUG, format="%(asctime)s %(name)s [%(levelname)s] %(funcName)s - %(message)s")
logger = logging.getLogger(__name__)

def get_default_outputbase(input_filename):
    """
    returns a tuple of (basename, extension) for a given input filename
    :param input_filename:
    :return:
    """
    input_file_basename = os.path.basename(input_filename)
    logger.debug("Input basename is {0}".format(input_file_basename))
    parts = re.match(r'^(.*)\.([^\.]+)$', input_file_basename)
    if parts:
        return parts.group(1), "." + parts.group(2)
    else:
        return input_file_basename, ""

###START MAIN
parser = argparse.ArgumentParser()
parser.add_argument("--input", dest='input_filename', help="File to split")
parser.add_argument("--output-base", dest='output_base', help="Basename of files to output")
parser.add_argument("--parts", dest='parts', help="Number of parts to output", default=4)
args = parser.parse_args()

parts_count = int(args.parts)
default_outbase, file_xtn = get_default_outputbase(args.input_filename)
logger.debug("Got {0} as base and {1} as extension".format(default_outbase, file_xtn))
if args.output_base:
    output_base = args.output_base
else:
    output_base = default_outbase

logger.debug("Output base is {0}".format(output_base))
output_filenames = []
for n in range(0, parts_count):
    output_filenames.append(output_base + "-{0}".format(n+1) + file_xtn)

logger.info("Splitting {0} into {1} chunks: {2}".format(args.input_filename, parts_count, output_filenames))
output_files = list(map(lambda filename: open(filename, "w"), output_filenames))


with open(args.input_filename, "r") as fpin:
    ctr = 0
    for line in fpin:
        output_files[ctr].write(line)
        ctr+=1
        if ctr>=len(output_files):
            ctr=0

for fp in output_files:
    fp.close()
