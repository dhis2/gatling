# Gatling Log Parser CLI

A CLI to turn Gatling binary `simulation.log` files into CSV files.

## Overview

This CLI uses Gatling's internal binary log parser to extract performance test data from
`simulation.log` files and creates `simulation.csv` next to them.

## Quick Start

Build the CLI

```sh
./build-glog.sh
```

Use the CLI

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

