#!/usr/bin/env bash

set -e

sbt clean scalafmtCheckAll compile test riffRaffUpload