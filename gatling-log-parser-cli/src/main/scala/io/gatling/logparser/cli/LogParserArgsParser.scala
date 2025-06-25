/*
 * Copyright 2011-2025 GatlingCorp (https://gatling.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.gatling.logparser.cli

import io.gatling.logparser.cli.StatusCode

final class LogParserArgsParser(args: Array[String]) {

  def parseArguments: Either[LogParserArgs, StatusCode] = {
    val argList = args.toList
    parseArgs(argList) match {
      case Some(parsedArgs) => Left(parsedArgs)
      case None             => printUsageAndExit()
    }
  }

  private def parseArgs(args: List[String]): Option[LogParserArgs] = {
    @scala.annotation.tailrec
    def loop(remaining: List[String], configPath: Option[String], debugEnabled: Boolean, scanSubdirs: Boolean, logFile: Option[String]): Option[LogParserArgs] =
      remaining match {
        case "--config" :: config :: rest =>
          loop(rest, Some(config), debugEnabled, scanSubdirs, logFile)
        case "--debug" :: rest =>
          loop(rest, configPath, debugEnabled = true, scanSubdirs, logFile)
        case "--scan-subdirs" :: rest =>
          loop(rest, configPath, debugEnabled, scanSubdirs = true, logFile)
        case "--help" :: _ | "-h" :: _ =>
          None
        case file :: Nil if !file.startsWith("-") =>
          loop(Nil, configPath, debugEnabled, scanSubdirs, Some(file))
        case Nil =>
          (configPath, logFile) match {
            case (Some(config), Some(log)) => Some(LogParserArgs(config, log, debugEnabled, scanSubdirs))
            case _                         => None
          }
        case _ =>
          None
      }

    args match {
      case "--help" :: _ | "-h" :: _ | Nil => None
      case _                               => loop(args, None, debugEnabled = false, scanSubdirs = false, None)
    }
  }

  private def printUsageAndExit(): Either[LogParserArgs, StatusCode] = {
    println("Gatling Log Parser CLI")
    println()
    println("Usage: glog --config <config> [--debug] [--scan-subdirs] <path>")
    println()
    println("Options:")
    println("  --config <path> Path to gatling.conf configuration file (required)")
    println("  --debug         Enable debug logging output")
    println("  --scan-subdirs  Scan immediate subdirectories for simulation.log files")
    println("  --help          Show this help message")
    println()
    println("Arguments:")
    println("  path    Path to simulation.log file or directory to scan")
    println()
    Right(StatusCode.Success)
  }
}
