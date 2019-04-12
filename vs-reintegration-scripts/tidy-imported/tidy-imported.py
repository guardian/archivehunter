#!/usr/bin/env python

import argparse
from gnmvidispine.vs_item import VSItem, VSNotFound
import csv
import logging
import yaml
from validate_media import find_s3_media
from pprint import pprint
from datetime import datetime

logger = logging.getLogger(__name__)
temp = logging.getLogger("urllib3")
temp.setLevel(logging.WARN)
temp = logging.getLogger("botocore")
temp.setLevel(logging.WARN)
temp = logging.getLogger("gnmvidispine.vidispine_api")
temp.setLevel(logging.WARN)
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s [%(levelname)s] %(funcName)s - %(message)s")


def read_list(filename):
    with open(filename, "r") as f:
        reader = csv.reader(f)
        for line in reader:
            if len(line)<3:
                continue

            yield {
                'media_path': line[0],
                'downloaded_proxy': line[1],
                'vsid': line[2]
            }


def get_credentials_from_yaml(filename):
    """
    load the yaml and extract out user, password keys then return as a tuple. raise if it fails
    :param filename:  file to read
    :return:  tuple of username, password
    """
    with open(filename, "r") as f:
        content = yaml.load(f.read())
        return (content["user"], content["password"])


def item_archived_md(bucket, path, s3_ctime):
    """
    set the fields on the item to show that it has been archived
    :param item:
    :return:
    """
    return {
        'gnm_external_archive_external_archive_request': "None",
        'gnm_external_archive_external_archive_status': "Archived",
        'gnm_external_archive_external_archive_device': bucket,
        'gnm_external_archive_external_archive_path': path,
        'gnm_external_archive_committed_to_archive_at': s3_ctime.isoformat('T'),
        'gnm_external_archive_external_archive_report': "{0}: Verified by Mr Pushy/ArchiveHunter".format(datetime.now().isoformat('T')),
        'gnm_external_archive_delete_shape': 'original'
    }


def filesize_for_shape(vsshape):
    """
    find the file size of a shape. This loops through all files present and gets the size of the first one with a valid size
    :param vsshape:
    :return: the size, or raises if none can be found
    """
    for file_entry in vsshape.files():
        if file_entry.size is not None and int(file_entry.size)>0:
            return int(file_entry.size)
    return -1


def handle_item_blank(entry, vsitem, bucket):
    """
    handle an item that is not tagged as archived right now
    :param entry:
    :param vsitem:
    :param bucket:
    :return:
    """

    #step one - double-check existence in S3
    mediainfo = find_s3_media(bucket, entry['media_path']) # raises if not present

    #step two - verify file size
    try:
        original_shape = vsitem.get_shape("original")
        file_size = filesize_for_shape(original_shape)
        logger.info("Size according to VS: {0}".format(file_size))

        logger.info("Size according to S3: {0}".format(mediainfo['size']))
        if file_size>0 and int(mediainfo['size']) != file_size:
            logger.error("Sizes do not match, difference is {0}".format(int(mediainfo['size'])-file_size))
            return

        has_original_shape = True
    except VSNotFound:
        logger.info("Item has no original shape")
        has_original_shape = False

    #step three - set up metadata
    newmd = item_archived_md(bucket, entry['media_path'], mediainfo['timestamp'])
    pprint(newmd)
    builder = vsitem.get_metadata_builder()
    builder.addMeta({'gnm_asset_status': 'Archived to External', 'gnm_storage_rule_projects_completed': 'storage_rule_projects_completed', 'gnm_storage_rule_deep_archive': "storage_rule_deep_archive"})
    builder.addGroup("ExternalArchiveRequest",newmd)
    builder.commit()

    #step four - remove original shape

    if has_original_shape:
        original_shape = vsitem.get_shape("original")
        original_shape.delete()

    logger.info("Configured untagged item {0}".format(vsitem.name))


def extract_fields_to_set(item, updated_meta):
    """
    takes an item and a dictionary of updates and removes un-necessary ones from the dictionary
    :param item: VSItem
    :param updated_meta: dict of metadata updates
    :return: a dictionary containing only the entries form updated_meta that are not already present in the items' metadata
    """
    return dict(filter(lambda tuple: tuple[1]!=item.get(tuple[0]), updated_meta.items()))


def handle_upload_failed(entry, vsitem, bucket):
    """
    handle an item that is tagged as "Upload Failed"
    :param entry:
    :param vsitem:
    :param bucket:
    :return:
    """

    #step one - double-check existence in S3
    mediainfo = find_s3_media(bucket, entry['media_path']) # raises if not present

    #step two - verify file size
    try:
        original_shape = vsitem.get_shape("original")
        file_size = filesize_for_shape(original_shape)
        logger.info("Size according to VS: {0}".format(file_size))

        logger.info("Size according to S3: {0}".format(mediainfo['size']))
        if file_size>0 and int(mediainfo['size']) != file_size:
            logger.error("Sizes do not match, difference is {0}".format(int(mediainfo['size'])-file_size))
            return

        has_original_shape = True
    except VSNotFound:
        logger.info("Item has no original shape")
        has_original_shape = False

    #step three - now we have verified a good upload, fix up the metadata
    assumed_metadata = item_archived_md(bucket, entry['media_path'], mediainfo['timestamp'])
    for k,v in assumed_metadata.items():
        logger.info("{field} - Expected: {expected}, Actual: {actual}".format(field=k, expected=v, actual=item.get(k)))
    to_set = extract_fields_to_set(item, assumed_metadata)
    #fix up log parameter
    to_set['gnm_external_archive_external_archive_report'] = assumed_metadata['gnm_external_archive_external_archive_report'] + "\n" + item.get('gnm_external_archive_external_archive_report')
    logger.info("items to set: {0}".format(to_set))

    builder = item.get_metadata_builder()
    rootmeta = extract_fields_to_set(item, {'gnm_asset_status': 'Archived to External', 'gnm_storage_rule_projects_completed': 'storage_rule_projects_completed', 'gnm_storage_rule_deep_archive': "storage_rule_deep_archive"})
    logger.info(rootmeta)
    builder.addMeta(rootmeta)
    builder.addGroup("ExternalArchiveRequest", to_set)
    builder.commit()

    #step four - if an original shape is present we can delete it (good upload was verified in steo two)
    if has_original_shape:
        item.get_shape("original").delete()
    else:
        logger.warning("Item {0} is marked as upload failed, but no original shape present! ".format(vsitem.name))


###START MAIN
parser = argparse.ArgumentParser()
parser.add_argument("--list", dest='listfile')
parser.add_argument("--host", dest='host', help="Host that pluto is running on", default="localhost")
parser.add_argument("--port", dest='port', help="Port for pluto", default=443)
parser.add_argument("--authfile", dest='authfile')
parser.add_argument("--archivehunter-collection", dest='ah_collection', help="Collection name in ArchiveHunter that these items belong to")
parser.add_argument("--asset-folder-root", dest='assetroot', help="Base path of asset folders for pluto", default="/srv/media")
parser.add_argument("--insecure", dest='insecure', help='use http instead of https', action='store_true')
parser.add_argument("--output-file", dest='output', help='write list to this file', default='in_vidispine.csv')
args = parser.parse_args()

auth = get_credentials_from_yaml(args.authfile)

interesting_fields = ["title","gnm_asset_category","gnm_asset_status","gnm_external_archive_external_archive_status","gnm_external_archive_external_archive_device", "gnm_external_archive_external_archive_path"]

for entry in read_list(args.listfile):
    if entry['vsid'] == "":
        continue

    item = VSItem(host=args.host, user=auth[0], passwd=auth[1])
    item.populate(entry['vsid'], specificFields=interesting_fields)

    logger.info("Got {0}".format(entry['vsid']))
    for f in interesting_fields:
        logger.info("\t{0}: {1}".format(f, item.get(f)))

    for shape in item.shapes():
        logger.info("\tShape {0}:".format(shape.tag()))
        for filepath in shape.fileURIs():
            logger.info("\t\t{0}".format(filepath))

    if item.get("gnm_external_archive_external_archive_status") is None or item.get("gnm_external_archive_external_archive_status")=="None" or item.get("gnm_external_archive_external_archive_status")=="":
        handle_item_blank(entry, item, args.ah_collection)
    if item.get("gnm_external_archive_external_archive_status") == "Upload Failed":
        handle_upload_failed(entry, item, args.ah_collection)
