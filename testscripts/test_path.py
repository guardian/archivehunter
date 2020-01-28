#!/usr/local/bin/python3

import hashlib
import hmac
from optparse import OptionParser
from datetime import datetime
import base64
from email.utils import formatdate
import requests
from time import mktime
from urllib.parse import urlparse, quote_plus
from pprint import pprint
import json
import os
import csv


def get_token(uri, secret):
    httpdate = formatdate(timeval=mktime(datetime.now().timetuple()), localtime=False, usegmt=True)
    url_parts = urlparse(uri)
    string_to_sign = "{0}\n{1}".format(httpdate, url_parts.path)
    hm = hmac.new(secret.encode('utf-8'), string_to_sign.encode('utf-8'), hashlib.sha256)
    return "HMAC {0}".format(base64.b64encode(hm.digest()).decode()), httpdate

#START MAIN
parser = OptionParser()
parser.add_option("-s", "--secret", dest="secret", help="shared secret to use")
parser.add_option("-p", dest="test_path", help="The local path to test")
parser.add_option("-r", dest="remove_path_segment", help="Segment to remove from the start of the path before testing", default="/Volumes/Multimedia2/DAM/Scratch/")

(options, args) = parser.parse_args()

if options.secret is None:
    print("You must supply the password in --secret")
    exit(1)


test_path = options.test_path

print("Path to test is " + test_path)


for root, dirs, files in os.walk(test_path, topdown=False):
    for name in files:
        whole_path = os.path.join(root, name)
        print(whole_path)
        smaller_path = whole_path.replace(options.remove_path_segment, "")
        path_for_archiver_hunter = quote_plus(smaller_path)
        uri = 'https://archivehunter.multimedia.gutools.co.uk/api/searchpath?filePath={0}'.format(path_for_archiver_hunter)
        authtoken, httpdate = get_token(uri, options.secret)
        headers = {
            'X-Gu-Tools-HMAC-Date': httpdate,
            'X-Gu-Tools-HMAC-Token': authtoken,
        }
        extra_kwargs = {}
        response = requests.get(uri, headers=headers, **extra_kwargs)
        print("Server returned {0}".format(response.status_code))
        pprint(response.headers)
        file_found = False
        if response.status_code==200:
            pprint(response.json())
            data_object = response.json()
            size_on_local = os.path.getsize(whole_path)
            if data_object['entries'][0]['size'] == size_on_local:
                print('Sizes match. File likely found.')
                file_found = True
        else:
            print(response.text)

        fd = open('file_report.csv','a')
        cvswriter = csv.writer(fd, delimiter=',', quotechar='|', quoting=csv.QUOTE_MINIMAL)
        cvswriter.writerow([whole_path, file_found])
        fd.close()
