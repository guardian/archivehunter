# vs-reintegration-scripts

This directory contains a set of Python scripts, runnable locally or via Docker, to assist in linking media managed in ArchiveHunter
to placeholder items in the Vidispine asset management system.

It's not likely to be of much use to anyone except us, but is included to show working examples of how to talk to ArchiveHunter
directly via a REST API.

## Workflow

1. `download-proxies-forlist`.  Run this script against ArchiveHunter, with a list of the filepaths (in S3) that you
need to re-integrate.  It will find them in AH, download a proxy if one is available or request the creation of a new
proxy if one is not available.
It outputs a new list, in CSV format, with columns for the original media path and corresponding downloaded proxy path

2. `reintegrate-from-list`.  Run this script pointing to your Vidispine, armed with the CSV output by stage 1.
It will attempt to find each item in Vidispine (using the custom field that we use to track where the media was originally
ingested from; `gnm_asset_filename`) and will output another CSV list containing the information of the first but with the
Vidispine ID, `gnm_external_archive_external_archive_status` and `gnm_external_archive_external_archive_status` added as 
extra columns.  This lets you check whether the item is actually existing in VS and whether it is pushed to archive or not.

3. `create-missing-from-list`.  Run this script with the CSV output by stage 1.  It needs access to ArchiveHunter and
Vidispine and it creates placeholder items in Vidispine, fully configured to the GNM metadata spec based on the limited
metadata available.  It pre-populates it to an "archived" state and links to the original media in S3, and it
adds the downloaded proxy as a lowres / lowaudio shape.  It also talks to the `gnm_asset_folder` custom Portal plugin
to ascertain the project ownership based on the filepath that the media came from.

## Running the scripts

I tend to find that the easiest way to do long, unattended runs on the server is to package into a Docker container
and run like this:

```
docker run -v {host-path-to-holdingarea}:{host-path-to-holdingarea} -v {host-path-to-authfile}:/mnt/authfile [-v {hostpath-to-sourcelist}:/mnt/bigpush.lst] \
    {docker-path-to-your-image}:{revision} /usr/local/scripts/{scriptname}.py --list /mnt/bigpush.lst --collection {s3-bucketname} \
     --hostname {archivehunter-host} --secret {archivehunter-secret} --strip {path-component-count-to-remove} \
     --holding-path {host-path-to-holdingarea} --output-list {host-path-to-holdingarea}/proxy-downloads-2.csv > ~/logfile.log 2>&1 </dev/null &
```

- I keep the host path and bind-mounted path of the holding area the same, this means that the output CSVs keep valid paths
both from the perspective of the container and the host
- Often the path to media on the host is longer than in S3.  Specifying the `--strip` argument removes a number of components
from the beginning of the path so that media can be found (e.g., `/Volumes/media/Path/to/my/file` with `--strip 2` becomes `Path/to/my/file`)
- By redirecting output and pushing to the background, I get the logs kept in a logfile on the host.  `tail -f ~/logfile.log` can
be used to monitor the process but it doesn't matter if i disconnect and log out, the process keeps going.
- I usually build and push to our internal repo, but there is no reason not to use Docker hub if that's what you want.

## Building a Docker container

The scripts need Python 3.5+ and have some module requirements, so it's convenient to build this into a container
then you don't need to mess with your server setups.  Simply run:

`docker build . -t {your-repo}/{yourname}/vs-reintegration-scripts:{revision}`, substituting the bits in braces,
to get a container with the scripts installed in /usr/local/bin and their module requirements (as listed in requirements.txt)
installed to.