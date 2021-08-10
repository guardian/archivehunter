#!/usr/bin/env python3

import boto3
import logging

source_table = ""

ddb = boto3.resource("dynamodb")


class OperationBuffer(object):
    def __init__(self):
        pass


def iterate_source_records(source_table_name:str):
    """
    a generator that yields records from the source table
    :param source_table_name:
    :return:
    """
    logger = logging.getLogger("iterate_source_records")
    continuation = None
    while True:
        response = ddb.scan(
            TableName=source_table_name,
            ExclusiveStartKey=continuation
        )
        logger.debug("page response is {}".format(response))
        if response["LastEvaluatedKey"] is None:
            break

        for entry in response["Items"]:
            yield entry

        continuation = {"S":response["LastEvaluatedKey"]}
    logger.info("Finished iterating source records")

###START MAIN
