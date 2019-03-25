import threading
from queue import Queue, Empty
import logging
import csv
import re


class ListWriterThread(threading.Thread):
    filename_splitter=re.compile(r'^(.*)\.([^.]+)')

    def __init__(self, *args, queue=None, thread_timeout=3600, output_file="output.lst", **kwargs):
        super(ListWriterThread, self).__init__(*args,**kwargs)
        if not isinstance(queue, Queue): raise TypeError

        self.logger = logging.getLogger(__name__)
        self._q = queue
        self.thread_timeout = thread_timeout
        self.output_file = output_file

    def run(self):
        """
        thread mainloop. dequeues an item off the queue and initiates it; terminates if the item is None
        :return: None
        """
        with open(self.output_file,"w") as output:
            self.logger.info("Writing to {0}".format(self.output_file))
            writer = csv.writer(output)
            while True:
                item = None
                try:
                    item = self._q.get(timeout=self.thread_timeout)
                    if item is None:
                        self.logger.info("Terminating thread due to program request")
                        return
                    self.process_item(item, writer)
                    output.flush()
                except Empty:
                    self.logger.error("Writer thread timed out")
                    return

                except Exception:
                    if item:
                        self.logger.exception("Could not process {0}".format(item))
                    else:
                        self.logger.exception("Thred error with no item")

    def process_item(self, item, writer):
        """
        process an individual item.
        expects a dict containing keys for media_path, proxy_path, error
        :param item: dict describing the item that has been processed
        :param writer: a CSV writer
        :return:
        """
        writer.writerow([item["media_path"], item["proxy_path"], item["error"]])
