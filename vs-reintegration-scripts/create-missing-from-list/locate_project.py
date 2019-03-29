import requests
import logging
import urllib.parse

logger = logging.getLogger(__name__)


class HttpError(Exception):
    pass


class ProjectLocator(object):
    """
    caching lookup for a project by asset folder path
    """
    def __init__(self, host, port, user, passwd, proto="https"):
        self._host = host
        self._port = port
        self._user = user
        self._passwd = passwd
        self._proto = proto

        self._cache = {}

    def lookup_path(self, path):
        if path in self._cache:
            logger.debug("cache hit on {0}".format(path))
            return self._cache[path]

        req_url = "{proto}://{host}/gnm_asset_folder/lookup?path={p}".format(proto=self._proto, host=self._host, p=urllib.parse.quote(path))

        result = requests.get(req_url, auth=(self._user, self._passwd), verify=False)
        if result.status_code!=200:
            logger.error("Error {0} accessing {1}:".format(result.status_code, req_url))
            logger.error(result.text)
            raise HttpError("Server returned {0}".format(result.status_code))

        data = result.json()
        logger.info("Got {0}".format(result.json()))
        self._cache[path] = data["project"]
        return data["project"]
