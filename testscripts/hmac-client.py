#!/usr/local/bin/python3

import hashlib
import hmac
from optparse import OptionParser
from datetime import datetime
import base64
from email.utils import formatdate
import requests
from time import mktime
from urllib.parse import urlparse
from pprint import pprint
import json

def get_token(uri, secret):
    httpdate = formatdate(timeval=mktime(datetime.now().timetuple()),localtime=False,usegmt=True)
    url_parts = urlparse(uri)

    string_to_sign = "{0}\n{1}".format(httpdate, url_parts.path)
    print("string_to_sign: " + string_to_sign)
    hm = hmac.new(secret.encode('utf-8'), string_to_sign.encode('utf-8'), hashlib.sha256)
    return "HMAC {0}".format(base64.b64encode(hm.digest()).decode()), httpdate

#START MAIN
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

if options.remove:
    uri = "https://{host}/api/proxy/{fileid}/{proxytype}".format(host=options.host,fileid=options.entry_id, proxytype=options.proxy_type)
elif options.query:
    uri = "https://{host}/api/proxy/{fileid}/all".format(host=options.host, fileid=options.entry_id)
elif options.raw:
    uri = options.raw
else:
    uri = "https://{host}/api/proxy".format(host=options.host)

print("uri is " + uri)
authtoken, httpdate = get_token(uri, options.secret)
print(authtoken)

headers = {
        'X-Gu-Tools-HMAC-Date': httpdate,
        'X-Gu-Tools-HMAC-Token': authtoken,
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
