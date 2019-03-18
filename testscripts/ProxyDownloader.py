import threading
from queue import Queue, Empty
import logging
import requests
from ArchiveHunterHmac import signed_headers
import os.path
import re

class ProxyDownloader(threading.Thread):
    filename_splitter=re.compile(r'^(.*)\.([^.]+)')

    def __init__(self, *args, queue=None, thread_timeout=60, hostname="", secret="", target_path="/tmp", chunk_size=8192, **kwargs):
        super(ProxyDownloader, self).__init__(*args,**kwargs)
        if not isinstance(queue, Queue): raise TypeError

        self.logger = logging.getLogger(__name__)
        self._q = queue
        self.thread_timeout = thread_timeout
        self.hostname = hostname
        self.secret = secret
        self.target_path = target_path
        self.chunk_size = chunk_size

    def run(self):
        """
        thread mainloop. dequeues an item off the queue and initiates it; terminates if the item is None
        :return: None
        """
        while True:
            item = None
            try:
                item = self._q.get(timeout=self.thread_timeout)
                if item is None:
                    self.logger.info("Terminating thread due to program request")
                    return
                self.process_item(item)

            except Empty:
                self.logger.error("Thread ran out of things to process")
                return

            except Exception:
                if item:
                    self.logger.exception("Could not process {0}".format(item))
                else:
                    self.logger.exception("Thred error with no item")

    def get_download_url(self, archive_hunter_id):
        uri = "https://{host}/api/proxy/{id}/playable".format(host=self.hostname, id=archive_hunter_id)
        self.logger.debug("URI is {0}".format(uri))
        headers = signed_headers(uri, self.secret)

        response = requests.get(uri, headers=headers)
        try:
            if response.status_code==200:
                content = response.json()
                self.logger.debug(str(content))
                return content["uri"]
            else:
                self.logger.error(response.text)
                raise Exception("Server returned {0}".format(response.status_code))
        except Exception as e:
            self.logger.exception("Could not get download URL for {0}".format(archive_hunter_id))
            return None

    def find_acceptable_filename(self, filename_no_ext, ext, counter=0):
        """
        recursively find an available filename in the requested download location
        :param filename_no_ext: filename with no extension
        :param ext: file extension
        :param counter:
        :return:
        """
        if counter==0:
            counter_string = ""
        else:
            counter_string = "-{0}".format(counter)

        download_path = os.path.join(self.target_path, "{filename}{counter}.{extension}".format(filename=filename_no_ext, counter=counter_string, extension=ext))
        if(os.path.exists(download_path)):
            self.logger.debug("Path already exists: {0}".format(download_path))
            return self.find_acceptable_filename(filename_no_ext, ext, counter=counter+1)
        else:
            return download_path

    def do_download(self, item, download_uri):
        parts = self.filename_splitter.match(item["name"])
        if parts:
            filename_no_ext = parts.group(1)
            extension = parts.group(2)
        else:
            filename_no_ext = item["name"]
            extension=""

        download_path = self.find_acceptable_filename(filename_no_ext, extension)
        self.logger.info("Download path is {0}".format(download_path))

        with requests.get(download_uri, stream=True) as r:
            r.raise_for_status()
            with open(download_path, "wb") as f:
                for chunk in r.iter_content(chunk_size=self.chunk_size):
                    if chunk:   #we get keepalive chunks apparently which evaluate to false
                        f.write(chunk)

        self.logger.info("Download completed")

    def process_item(self, item):
        """
        process the given item
        :param item: item to process. This should be a dict containing keys "path" (source file path), "name" (source file name),
        "archive_hunter_id" (archive hunter ID of the file)
        :return:
        """

        download_url = self.get_download_url(item["archive_hunter_id"])
        if download_url is None:
            self.logger.error("Could not get any download URL for {0}".format(item))
            return

