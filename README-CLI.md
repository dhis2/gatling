# Gatling Log Parser CLI

A CLI to turn Gatling binary `simulation.log` files into CSV files.

## Overview

This CLI uses Gatling's internal binary log parser to extract performance test data from
`simulation.log` files and creates `simulation.csv` next to them.

## Installation

### Option 1: Download Release (Recommended)

Download the [latest release](https://github.com/dhis2/gatling/releases/latest), then:

```sh
# Extract the downloaded glog-X.Y.Z.zip
unzip glog-*.zip
cd glog

# Use the CLI
./bin/glog --config gatling.conf simulation.log
```

**Requirements:** Java 17 or later

### Option 2: Build from Source

Build the CLI locally:

```sh
./build-glog.sh
```

Use the CLI:

```sh
./glog --config gatling.conf simulation.log
./glog --config gatling.conf --scan-subdirs /path/to/gatling-report
```

## Development

Compile and test

```sh
sbt "project gatling-log-parser-cli" compile
sbt "project gatling-log-parser-cli" test
```

Run directly with SBT

```sh
sbt "project gatling-log-parser-cli" "run --config gatling.conf simulation.log"
```

## Releasing

To create a new release:

1. Commit and push your changes to the `glog-cli` branch
2. Create and push a tag with the format `glog-vX.Y.Z`:

```sh
git tag glog-v0.0.2
git push dhis2 glog-v0.0.2
```

3. GitHub Actions will automatically:
   * Build the CLI
   * Create `glog-X.Y.Z.zip`
   * Publish to https://github.com/dhis2/gatling/releases/tag/glog-vX.Y.Z
   * Mark it as the latest release

**Note:** Only tags matching the pattern `glog-v*.*.*` will trigger releases. This prevents
accidental releases from upstream Gatling tags.

## Build Options

`./build-glog.sh` creates

* `./glog` - JVM executable (requires Java 17+)
* Distribution ZIP - for sharing/deployment

## Command Line Options

* `--config <path>`: **required** path to `gatling.conf` configuration file used to generate
Gatlings `simulation.log`
* `--debug`: enable debug logging output
* `--scan-subdirs`: scan subdirectories (only 1 level deep) for `simulation.log` files
* `<path>`: path to `simulation.log` file or directory

## CSV Output Format

Creates `simulation.csv` with the following columns:

```
record_type,scenario_name,group_hierarchy,request_name,status,start_timestamp,end_timestamp,response_time_ms,error_message,event_type,duration_ms,cumulated_response_time_ms,is_incoming
```

Record Types

* `request`: HTTP requests with response times and status
* `user`: User lifecycle events (start/end)
* `group`: Request group timings
* `error`: Error records

