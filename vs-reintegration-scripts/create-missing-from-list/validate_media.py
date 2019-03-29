import boto3
import logging

logger = logging.getLogger(__name__)


def find_s3_media(bucketname, path):
    """
    locate the given media in S3 and return some basic metadata
    :param bucketname:
    :param path:
    :return:
    """
    client = boto3.client('s3')

    result = client.head_object(Bucket=bucketname, Key=path)
    logger.debug("Got metadata for {0}:{1}; {2}".format(bucketname, path, result))

    return {
        "timestamp": result["LastModified"],
        "size": result["ContentLength"],
        "etag": result["ETag"],
        "type": result["ContentType"]
    }