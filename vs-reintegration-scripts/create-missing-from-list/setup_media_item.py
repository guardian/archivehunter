from gnmvidispine.vs_item import VSItem
from gnmvidispine.vs_job import VSJob
from gnmvidispine.vs_collection import VSCollection
import re
import logging
from datetime import datetime
import urllib.parse
from time import sleep
import os.path

logger = logging.getLogger(__name__)


def format_timestamp(ts):
    if ts is None: return ""
    if not isinstance(ts, datetime): raise TypeError("format_timestamp requires a datetime, not {0}".format(ts.__class__.__name__))
    return ts.isoformat("T")


def configure_metadata(mdbuilder, full_filepath, collection_name, archive_path, archive_timestamp):
    """
    set up metadata as required for the item.
    :param mdbuilder: VSMetadataBuilder instance
    :return:
    """
    #category, status, original filename,deep archive, parent projects completed,
    # external archive fields, can get last mod date?

    mdbuilder.addMeta({
        "gnm_asset_category": "Rushes",
        "gnm_asset_status": "Archived to External",
        "gnm_asset_filename": full_filepath,
        "gnm_storage_rule_deep_archive": "storage_rule_deep_archive",
        "gnm_storage_rule_projects_completed": "storage_rule_projects_completed"
    })
    mdbuilder.addGroup("ExternalArchiveRequest", {
        "gnm_external_archive_external_archive_request": "None",
        "gnm_external_archive_external_archive_status": "Archived",
        "gnm_external_archive_external_archive_device": collection_name,
        "gnm_external_archive_external_archive_path": archive_path,
        "gnm_external_archive_committed_to_archive_at": format_timestamp(archive_timestamp),
        "gnm_external_archive_external_archive_report": """Output by Mr Pushy/Archive Hunter""",
        "gnm_external_archive_delete_shape": "original"
    }, mode="add")


def wait_for_job(vsjob):
    """
    wait for the provided VSJob to complete. Raises if the job fails.
    :param vsjob:  VSJob object to monitor
    :return: None, raises if the job fails
    """
    if not isinstance(vsjob,VSJob): raise TypeError("wait_for_job must be called with a VSJob, not a {0}".format(vsjob.__class__.__name__))

    logger.info("Waiting for VS job {0} to complete or fail...".format(vsjob.name))
    while True:
        sleep(10)
        vsjob.update()
        logger.debug("Checking {0}, job status is {1}".format(vsjob.name, vsjob.status()))
        if vsjob.finished(noraise=False):
            logger.info("Job {0} completed".format(vsjob.name))
            break


extension_extractor = re.compile(r'\.([^\.]+)$')


def shape_tag_for_filepath(proxy_filepath):
    groups = extension_extractor.search(proxy_filepath)
    if groups is not None:
        xtn = groups.group(1)
        if(xtn=="mp3"): return "lowaudio"
        if(xtn=="mp4"): return "lowres"
        if(xtn=="jpg"): return "lowimage"
        raise Exception("Don't know what shape a {0} proxy should be, for {1}".format(xtn, proxy_filepath))
    else:
        raise Exception("No file extension for {0}".format(proxy_filepath))


def setup_item(host, user, passwd, full_filepath, proxy_filepath, collection_name, archive_path, archive_timestamp, parent_project_id, wait=False):
    """
    create and populate a placeholder item for the given media in Vidispine
    :param host: VS hostname
    :param user:  VS user id
    :param passwd:  VS password
    :param full_filepath: full path to the media AS IT WOULD EXIST LOCALLY (this is stored and later used for restore location)
    :param proxy_filepath: full path the the proxy AS IT WILL APPEAR TO VIDISPINE SERVER
    :param collection_name: ArchiveHunter collection (bucket name) that stores the media
    :param archive_path: path to the media in the bucket
    :param archive_timestamp: timestamp that the item was added to archive
    :param parent_project_id: Vidispine ID of the parent project
    :param wait: whether to wait for the ingest operations to complete before proceeding. Default is FALSE.
    :return: the populated VSItem object. If an operation fails, the partially created item is deleted and the exception re-raised.
    """
    #step one - create placeholder
    item = VSItem(host=host, user=user, passwd=passwd)
    item.createPlaceholder(metadata={'title': os.path.basename(full_filepath), 'created': datetime.now().isoformat('T')}, group="Asset")
    logger.info("Created placeholder with item ID {0}".format(item.name))

    try:
        #step two - add metadata
        mdbuilder = item.get_metadata_builder()
        configure_metadata(mdbuilder, full_filepath, collection_name, archive_path, archive_timestamp)
        mdbuilder.commit()

        #step three - add proxy shape(s). Need to as as "original" too so that Portal realises it's not a placeholder and displays the proxy.
        tag = shape_tag_for_filepath(proxy_filepath)
        vsjob = item.import_to_shape(uri="file:///{0}".format(urllib.parse.quote(proxy_filepath)), shape_tag=["original", tag], essence=True, thumbnails=True)
        if wait:
            wait_for_job(vsjob)

        #step four - collection membership
        parent_collection = VSCollection(host=host, user=user, passwd=passwd)
        parent_collection.name = parent_project_id
        parent_collection.addToCollection(item)

        sleep(2)
        #step five - remove dummy original shape
        shape = item.get_shape('original')
        shape.delete()

        #step 6 - update invalid creation timestamp
        item.set_metadata({'created': datetime.now().isoformat('T')})

        #all done!
        return item
    except Exception as e:
        logger.exception("Could not set up item: ", e)
        item.delete()
        logger.info("Deleted partial item {0}".format(item.name))
        raise
