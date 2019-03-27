import hashlib
import hmac
from datetime import datetime
import base64
from email.utils import formatdate
from time import mktime
import urllib.parse


def get_token(uri, secret):
    httpdate = formatdate(timeval=mktime(datetime.now().timetuple()),localtime=False,usegmt=True)
    url_parts = urllib.parse.urlparse(uri)

    string_to_sign = "{0}\n{1}".format(httpdate, url_parts.path)
    hm = hmac.new(bytes(secret, "UTF-8"), bytes(string_to_sign, "UTF-8"),hashlib.sha256)
    return "HMAC {0}".format(base64.b64encode(hm.digest()).decode("UTF-8")), httpdate


def signed_headers(uri, secret):
    """
    generate a dict of signed headers for the given uri and secret
    :param uri: uri that you are trying to access
    :param secret: shared secret for the server
    :return: a dict of headers to add to your request
    """
    authtoken, httpdate = get_token(uri, secret)
    #print authtoken

    headers = {
        'X-Gu-Tools-HMAC-Date': httpdate,
        'X-Gu-Tools-HMAC-Token': authtoken,
    }

    return headers
