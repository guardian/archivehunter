#!/usr/bin/env python3

from typing import Union
from argparse import ArgumentParser
import boto3
import base64

MAX_ID_LENGTH=512
# val initialString = bucket + ":" + key
# if(initialString.length<=maxIdLength){
# encoder.encodeToString(initialString.toCharArray.map(_.toByte))
# } else {
# /* I figure that the best way to get something that should be unique for a long path is to chop out the middle */
# val chunkLength = initialString.length/3
# val stringParts = initialString.grouped(chunkLength).toList
# val midSectionLength = maxIdLength - chunkLength*2  //FIXME: what if chunkLength*2>512??
# val finalString = stringParts.head + stringParts(1).substring(0, midSectionLength) + stringParts(2)
# encoder.encodeToString(finalString.toCharArray.map(_.toByte))
# }


def make_id(bucket:str, entry:dict) -> str:
    initial_string = bucket + ":" + entry["Key"]

    if len(initial_string)<=MAX_ID_LENGTH:
        return base64.b64encode(initial_string.encode("UTF-8")).decode("UTF-8")
    else:
        chunk_length = len(initial_string) / 3
        mid_section_length = MAX_ID_LENGTH - chunk_length*2
        final_section_start = len(initial_string) - 2*chunk_length
        final_string = initial_string[0:chunk_length] + initial_string[chunk_length:mid_section_length+chunk_length] + initial_string[final_section_start:]
        return base64.b64encode(final_string.encode("UTF-8")).decode("UTF-8")


def handle_next_page(bucket:str, prefix:Union[str,None], continuation_token:Union[str,None]):
    s3_args = {
        "Bucket": bucket,
        "MaxKeys": 1000,
    }
    if prefix:
        s3_args["Prefix"] = prefix
    if continuation_token:
        s3_args["ContinuationToken"] = continuation_token

    response = client.list_objects_v2(**s3_args)
    for entry in response["Contents"]:
        print(make_id(bucket, entry))
    if "NextContinuationToken" in response:
        handle_next_page(bucket, prefix, response["NextContinuationToken"])


# START MAIN
parser = ArgumentParser()
parser.add_argument("--bucket", dest="bucket", help="bucket name to scan")
parser.add_argument("--prefix", dest="prefix", help="path prefix to output")
args = parser.parse_args()

client = boto3.client("s3")
prefix = None
if args.prefix != "":
    prefix = args.prefix

handle_next_page(args.bucket, prefix, None)
