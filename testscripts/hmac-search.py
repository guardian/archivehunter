#!/usr/bin/env python3

import hashlib
import hmac
import sys
import urllib.parse
from optparse import OptionParser
from datetime import datetime, timedelta
import base64
from email.utils import formatdate
import requests
from time import mktime
from urllib.parse import urlparse
import json


def checksum(content:bytes) -> str:
    """
    Calculates the SHA-384 checksum of the given content and returns the base64 encoded representation as a string
    :param content: the content to hash
    :return: a string representing the checksum
    """
    digest = hashlib.sha384(content).digest()
    return base64.b64encode(digest).decode("UTF-8")


def get_token(uri:str, secret:str, method:str, content:bytes, checksum:str) -> (str, str):
    """
    Generates an HMAC token
    :param uri:  URI that is going to be accessed
    :param secret: Shared secret with the server
    :param method: HTTP method for the request
    :param content: byte string of the request body. Can be empty.
    :param checksum: SHA-384 checksum of the body content. If content is empty then this should be the SHA checksum of an empty string
    :return: tuple consisting of the body of an "Authorization" header and the HTTP style date/time of the request.
    """
    t = datetime.now()
    httpdate = formatdate(timeval=mktime(t.timetuple()),localtime=False,usegmt=True)
    url_parts = urlparse(uri)

    content_length = len(content)

    string_to_sign = "{}\n{}\n{}\n{}\n{}".format(httpdate, content_length, checksum, method, url_parts.path + "?" + url_parts.query)
    hm = hmac.digest(secret.encode("UTF-8"), msg=string_to_sign.encode("UTF-8"), digest=hashlib.sha384)
    return "HMAC {0}".format(base64.b64encode(hm).decode("UTF-8")), httpdate


def get_next_page(start_at: int, page_size: int) -> bool:
    method = "POST"
    uri = "https://{host}/api/search/browser?start={start}&size={size}".format(host=options.host,
                                                                               start=start_at,
                                                                               size=page_size)

    content_body = json.dumps({"collection": options.dest}).encode("UTF-8")
    content_hash = checksum(content_body)

    authtoken, httpdate = get_token(uri, options.secret, method, content_body, content_hash)

    headers = {
        'Date': httpdate,
        'Authorization': authtoken,
        'X-Sha384-Checksum': content_hash,
        'Content-Type': "application/json"
    }

    extra_kwargs = {}
    if options.sslnoverify:
        extra_kwargs['verify'] = False

    response = requests.post(uri, data=content_body, headers=headers, **extra_kwargs)

    if response.status_code==200:
        content = response.json()
        sys.stderr.write("Total hits: {0}\n".format(content["entryCount"]))
        if len(content["entries"])==0:
            return False
        for e in content["entries"]:
            if not e["beenDeleted"]:
                print(e["id"])
        return True
    else:
        print(response.text)


if __name__=="__main__":
    parser = OptionParser()
    parser.add_option("--host", dest="host", help="host to access", default="archivehunter.local.dev-gutools.co.uk")
    parser.add_option("--no-verify", dest="sslnoverify", action="store_true", default="false", help="set this to disable SSL cert checking")
    parser.add_option("-s", "--secret", dest="secret", help="shared secret to use")
    parser.add_option("-c", "--collection", dest="dest", help="Collection (bucket) name to list")
    parser.add_option("--page-size", dest="page_size", default=100, help="page size")
    (options, args) = parser.parse_args()

    if options.secret is None:
        print("You must supply the password in --secret")
        exit(1)

    ctr = 0
    while True:
        if get_next_page(ctr, int(options.page_size)):
            ctr += options.page_size
        else:
            break
