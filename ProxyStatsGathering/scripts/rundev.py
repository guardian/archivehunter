#!/usr/bin/env python

import boto3
import subprocess
import os.path
import yaml
import logging
from optparse import OptionParser
from time import sleep
from datetime import datetime
from tzlocal import get_localzone

logging.basicConfig(level=logging.WARN)
logger = logging.getLogger(__name__)
logger.level = logging.INFO


class LogViewer(object):
    def __init__(self, log_group_name, log_stream_name):
        super(LogViewer, self).__init__()

        self.log_group_name = log_group_name
        self.log_stream_name = log_stream_name
        self.most_recent_timestamp=None
        self._client = boto3.client('logs', region_name=options.region)

    def get_loglines(self):
        results = self._client.get_log_events(logGroupName = self.log_stream_name, logStreamName = self.log_stream_name, startTime=self.most_recent_timestamp, startFromHead=True)

        if len(results['events'])>0:
            last_event = results['events'][-1]
            self.most_recent_timestamp=last_event['timestamp']

        return map(lambda entry: "{0}: {1}".format(datetime.from_timestamp(entry['timestamp']), entry['message']), results['events'])


def find_sbt_dir(starting_path):
    if starting_path=="" or starting_path=="/":
        raise StandardError("Could not find a build.sbt file in any specified path")

    logger.debug("at {0}".format(starting_path))
    if os.path.exists(os.path.join(starting_path, "build.sbt")):
        return starting_path
    else:
        return find_sbt_dir(os.path.abspath(os.path.join(starting_path, "..")))


def build_and_push(host, user, sbtdir):
    # proc = subprocess.Popen(["sbt", "-Ddocker.host={0}".format(host), "-Ddocker.username={0}".format(user)],
    #                         stdin=subprocess.PIPE, cwd=sbtdir)
    #
    # proc.communicate("""project proxyStatsGathering\ndocker:publish\nexit\n""")

    proc = subprocess.Popen(["sbt", "-Ddocker.host={0}".format(host), "-Ddocker.username={0}".format(user), "project proxyStatsGathering", "docker:publish"], cwd=sbtdir)
    proc.wait()
    if proc.returncode!=0:
        raise StandardError("sbt dockerPublish failed")


def extract_cf_outputs(cfinfo):
    return dict(map(lambda entry: (entry["OutputKey"], entry["OutputValue"]), cfinfo["Outputs"]))


def get_cloudformation_info(stackname):
    client = boto3.client('cloudformation',  region_name=options.region)
    result = client.describe_stacks(StackName=stackname)

    if len(result["Stacks"])==0:
        raise StandardError("Could not find cloudformation stack {0}".format(stackname))

    info = result["Stacks"][0]
    logger.info("Found stack {0} in status {1}".format(info["StackName"], info["StackStatus"]))
    return extract_cf_outputs(info)


def run_task(cluster_id, task_arn, subnet_list, sg_list, allow_external_ip):
    client = boto3.client('ecs', region_name=options.region)
    network_config = {
        "awsvpcConfiguration": {
            "subnets": subnet_list,
            "securityGroups": sg_list,
            "assignPublicIp": "ENABLED" if allow_external_ip else "DISABLED"
        }
    }
    result = client.run_task(cluster=cluster_id, taskDefinition=task_arn, networkConfiguration=network_config, launchType="FARGATE")

    if len(result["failures"])>0:
        for entry in result.failures:
            logger.error("\t{0}: {1}".format(entry["arn"], entry["reason"]))
        raise StandardError("Failed to start up container")

    logger.debug(result["tasks"][0])
    return {
        "task_arn": result["tasks"][0]["taskArn"],
        "cluster_arn": result["tasks"][0]["clusterArn"],
        "container_id": result["tasks"][0]["containers"][0]["containerArn"]
    }


def monitor_task(cluster_arn, task_arn): #, log_group_name, log_stream_name):
    client = boto3.client('ecs', region_name=options.region)
    #viewer = LogViewer(log_group_name, log_stream_name)
    while True:
        try:
            response = client.describe_tasks(cluster=cluster_arn, tasks=[task_arn],)

            info = response["tasks"][0]

            if "startedAt" in info:
                start_time = info["startedAt"]
            else:
                start_time = None

            if "stoppedAt" in info:
                finish_time = info["stoppedAt"]
            else:
                finish_time = None

            logger.info("Task status is {0} (desired status {1})".format(info["lastStatus"], info["desiredStatus"]))
            if start_time:
                logger.info("Running since {0} ({1})".format(start_time, datetime.now(get_localzone())-start_time))

            # for line in viewer.get_loglines():
            #     print line

            if finish_time:
                logger.info("Ran from {0} to {1}, total of {2}".format(start_time, finish_time, finish_time-start_time))
                break

        except KeyError as e:
            logger.error(str(e))
            logger.debug(str(info))

        sleep(10)


###START MAIN
parser = OptionParser()
parser.add_option("-c","--config", dest="configfile", help="Configuration YAML", default="ecs_rundev.yaml")
parser.add_option("-r","--region", dest="region", help="AWS region", default="eu-west-1")
parser.add_option("-s","--stackname", dest="stackname", help="Cloudformation stack that contains the deployed task")
(options, args) = parser.parse_args()

with open(options.configfile,"r") as f:
    config = yaml.load(f.read())

sbt_dir = find_sbt_dir(os.path.dirname(os.path.realpath(__file__)))
logger.info("Got SBT directory {0}".format(sbt_dir))

if not "docker" in config:
    raise StandardError("You must have a docker: section in the yaml config file")

build_and_push(config["docker"].get("host"), config["docker"].get("user"), sbt_dir)

cfinfo = get_cloudformation_info(options.stackname)
logger.debug(str(cfinfo))
if not "TaskDefinitionArn" in cfinfo:
    raise StandardError("No TaskDefinitionArn output in {0}".format(options.stackname))
logger.info("Got task ARN {0}".format(cfinfo["TaskDefinitionArn"]))

taskinfo = run_task(config["ecs"].get("cluster"), cfinfo["TaskDefinitionArn"], config["ecs"].get("subnets"), config["ecs"].get("security_groups"), config["ecs"].get("external_ip"))

logger.debug(str(taskinfo))

monitor_task(taskinfo["cluster_arn"], taskinfo["task_arn"])