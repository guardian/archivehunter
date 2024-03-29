#!/usr/bin/env python3

import hashlib
import hmac
from optparse import OptionParser
from datetime import datetime, timedelta
import base64
from email.utils import formatdate
import requests
from time import mktime
from urllib.parse import urlparse
from pprint import pprint
import json

#uncomment this block to get request-level debug output
# import logging
#
# try:
#     import http.client as http_client
# except ImportError:
#     # Python 2
#     import httplib as http_client
# http_client.HTTPConnection.debuglevel = 1
#
# # You must initialize logging, otherwise you'll not see debug output.
# logging.basicConfig()
# logging.getLogger().setLevel(logging.DEBUG)
# requests_log = logging.getLogger("requests.packages.urllib3")
# requests_log.setLevel(logging.DEBUG)
# requests_log.propagate = True


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

    string_to_sign = "{}\n{}\n{}\n{}\n{}".format(httpdate, content_length, checksum, method, url_parts.path)
    print("string_to_sign: " + string_to_sign)
    hm = hmac.digest(secret.encode("UTF-8"), msg=string_to_sign.encode("UTF-8"), digest=hashlib.sha384)
    return "HMAC {0}".format(base64.b64encode(hm).decode("UTF-8")), httpdate

#START MAIN
if __name__ == "__main__":
    parser = OptionParser()
    parser.add_option("--host", dest="host", help="host to access", default="archivehunter.local.dev-gutools.co.uk")
    parser.add_option("--no-verify", dest="sslnoverify", action="store_true", default="false", help="set this to disable SSL cert checking")
    parser.add_option("-s", "--secret", dest="secret", help="shared secret to use")
    parser.add_option("--id", dest="entry_id", help="ArchiveHunter ID of media to set proxy for")
    parser.add_option("-b", "--proxy-bucket", dest="proxy_bucket", help="Bucket where proxy is stored")
    parser.add_option("-p", "--proxy-path", dest="proxy_path", help="Path where proxy is stored")
    parser.add_option("-t", "--proxy-type", dest="proxy_type", help="Type of proxy to set")
    parser.add_option("-r", "--region", dest="region", help="Region of the proxy bucket")
    parser.add_option("--rm", dest="remove", help="Remove the given proxy as opposed to adding. You only need to specify --proxy-type and --id for this.", action="store_true")
    parser.add_option("-q", "--query", dest="query", help="Query what proxies are available for the given item", action="store_true")
    parser.add_option("--raw",dest="raw",help="Call the provided url and display the result")
    (options, args) = parser.parse_args()

    if options.secret is None:
        print("You must supply the password in --secret")
        exit(1)

    method = "GET"
    if options.remove:
        uri = "https://{host}/api/proxy/{fileid}/{proxytype}".format(host=options.host,fileid=options.entry_id, proxytype=options.proxy_type)
        method = "DELETE"
    elif options.query:
        uri = "https://{host}/api/proxy/{fileid}/all".format(host=options.host, fileid=options.entry_id)
    elif options.raw:
        uri = options.raw
    else:
        uri = "https://{host}/api/proxy".format(host=options.host)

    print("uri is " + uri)
    content_body = "".encode("UTF-8")
    content_hash = checksum(content_body)

    authtoken, httpdate = get_token(uri, options.secret, method, content_body, content_hash)
    print(authtoken)

    headers = {
            'Date': httpdate,
            'Authorization': authtoken,
            'X-Sha384-Checksum': content_hash,
    }

    print(headers)
    extra_kwargs = {}
    if options.sslnoverify:
        extra_kwargs['verify'] = False

    if options.remove:
        response = requests.delete(uri, headers=headers, **extra_kwargs)
    elif options.query:
        response = requests.get(uri, headers=headers, **extra_kwargs)
    elif options.raw:
        response = requests.get(uri, headers=headers, **extra_kwargs)
    else:
        requestbody = json.dumps({
            "entryId": options.entry_id,
            "proxyBucket": options.proxy_bucket,
            "proxyPath": options.proxy_path,
            "proxyType": options.proxy_type,
            "region": options.region
        })
        headers['Content-Type'] = "application/json"
        response = requests.post(uri, data=requestbody, headers=headers, **extra_kwargs)

    print("Server returned {0}".format(response.status_code))
    pprint(response.headers)
    if response.status_code==200:
        pprint(response.json())
    else:
        print(response.text)
