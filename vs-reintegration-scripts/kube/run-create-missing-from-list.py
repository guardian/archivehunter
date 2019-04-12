#!/usr/bin/env python

import argparse
import re
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument("--template", dest='template', help="template job manifest", default="template/create-missing-from-list-template.yaml")
parser.add_argument("--parts", dest='parts', help="Number of parts to output", default=4)
parser.add_argument("--runit", dest="runit", help="Run manifests as well as creating", action="store_true", default=False)
args = parser.parse_args()

parts_count = int(args.parts)

for n in range(0,parts_count):
    with open(args.template, "r") as fpread:
        with open("create-missing-from-list-{0}.yaml".format(n+1),"w") as fpout:
            for line in fpread:
                updated_line = re.sub(r"\{\{\s*job-number\s*\}\}", str(n+1), line)
                fpout.write(updated_line)

if args.runit:
    for n in range(0,parts_count):
        subprocess.call(["kubectl","apply","-f","create-missing-from-list-{0}.yaml".format(n+1)])
