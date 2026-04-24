/*
 * Copyright 2011-2026 GatlingCorp (https://gatling.io)
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

package io.gatling.logparser

import java.io.{ File, FileWriter, PrintWriter }

import scala.collection.mutable

import io.gatling.charts.stats._
import io.gatling.commons.stats.{ KO, OK }
import io.gatling.core.config.GatlingConfiguration
import io.gatling.core.stats.message.MessageEvent
import io.gatling.logparser.cli.{ LogParserArgs, LogParserArgsParser }

import ch.qos.logback.classic.{ Level, Logger }
import com.tdunning.math.stats.AVLTreeDigest
import com.typesafe.scalalogging.StrictLogging
import org.slf4j.LoggerFactory

object GatlingLogParser extends StrictLogging {

  def main(args: Array[String]): Unit = {
    // Set quiet logging by default, before any libraries initialize
    val rootLogger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).asInstanceOf[Logger]
    rootLogger.setLevel(Level.WARN)
    LoggerFactory.getLogger("io.gatling").asInstanceOf[Logger].setLevel(Level.ERROR)
    LoggerFactory.getLogger("io.netty").asInstanceOf[Logger].setLevel(Level.ERROR)
    LoggerFactory.getLogger("io.gatling.logparser.GatlingLogParser").asInstanceOf[Logger].setLevel(Level.INFO)

    val argsParser = new LogParserArgsParser(args)
    argsParser.parseArguments match {
      case Left(logParserArgs) =>
        val exitCode = run(logParserArgs)
        sys.exit(exitCode)
      case Right(statusCode) =>
        sys.exit(statusCode.code)
    }
  }

  private def run(args: LogParserArgs): Int = {
    // Enable debug logging if requested
    if (args.debugEnabled) {
      val rootLogger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).asInstanceOf[Logger]
      rootLogger.setLevel(Level.DEBUG)

      // Enable debug for Gatling and Netty
      LoggerFactory.getLogger("io.gatling").asInstanceOf[Logger].setLevel(Level.DEBUG)
      LoggerFactory.getLogger("io.netty").asInstanceOf[Logger].setLevel(Level.DEBUG)
    }

    val configFile = new File(args.configPath).getAbsoluteFile
    val inputPath = new File(args.logFilePath).getAbsoluteFile

    if (!configFile.exists()) {
      System.err.println(s"Config file not found: ${args.configPath}")
      System.err.println(s"Absolute path: ${configFile.getAbsolutePath}")
      1
    } else if (!inputPath.exists()) {
      System.err.println(s"Path not found: ${args.logFilePath}")
      System.err.println(s"Absolute path: ${inputPath.getAbsolutePath}")
      1
    } else {
      val logFilesResult = if (inputPath.isFile) {
        // Single file provided
        Right(List(inputPath))
      } else if (inputPath.isDirectory) {
        // Directory provided
        val directSimulationLog = new File(inputPath, "simulation.log")
        if (directSimulationLog.exists()) {
          // Directory contains simulation.log directly
          Right(List(directSimulationLog))
        } else if (args.scanSubdirs) {
          // Scan subdirectories for simulation.log files
          val subdirs = inputPath.listFiles().filter(_.isDirectory)
          val foundLogs = subdirs.flatMap { subdir =>
            val simulationLog = new File(subdir, "simulation.log")
            if (simulationLog.exists()) Some(simulationLog) else None
          }.toList

          if (foundLogs.isEmpty) {
            System.err.println(s"No simulation.log files found in subdirectories of: ${inputPath.getAbsolutePath}")
            Left(1)
          } else {
            logger.info(s"Found ${foundLogs.length} simulation.log files in subdirectories")
            Right(foundLogs)
          }
        } else {
          System.err.println(s"Directory does not contain simulation.log: ${inputPath.getAbsolutePath}")
          System.err.println("Use --scan-subdirs to scan immediate subdirectories")
          Left(1)
        }
      } else {
        System.err.println(s"Path is neither a file nor a directory: ${inputPath.getAbsolutePath}")
        Left(1)
      }

      logFilesResult match {
        case Left(errorCode) => errorCode
        case Right(logFiles) =>
          try {
            io.gatling.core.stats.writer.StringInternals.checkAvailability() // Ensure method handle is initialized
          } catch {
            case e: IllegalAccessException =>
              logger.warn("Could not initialize StringInternals due to module access restrictions. Continuing anyway.", e)
          }

          val configuration = GatlingConfiguration.loadFromFile(configFile)
          var processedCount = 0
          var failedCount = 0

          logFiles.foreach { logFile =>
            try {
              logger.debug(s"Processing: ${logFile.getAbsolutePath}")
              val logFileReader = new LogFileReader(logFile, configuration)
              val records = logFileReader.parseRaw()

              // Create output file paths next to the simulation.log
              val baseName = logFile.getName.replaceAll("\\.log$", "")
              val outputFile = new File(logFile.getParentFile, s"$baseName.csv")
              val percentilesFile = new File(logFile.getParentFile, s"$baseName-percentiles.csv")
              logger.debug(s"Writing CSV output to: ${outputFile.getAbsolutePath}")
              logger.debug(s"Writing percentiles CSV output to: ${percentilesFile.getAbsolutePath}")

              val writer = new PrintWriter(new FileWriter(outputFile))
              try {
                outputCsv(records, writer)
              } finally {
                writer.close()
              }

              val percentilesWriter = new PrintWriter(new FileWriter(percentilesFile))
              try {
                outputPercentilesCsv(records, percentilesWriter)
                processedCount += 1
              } finally {
                percentilesWriter.close()
              }
            } catch {
              case e: java.io.EOFException =>
                logger.error(s"Failed to process ${logFile.getAbsolutePath} - file is truncated or empty")
                failedCount += 1
              case e: Exception =>
                logger.error(s"Failed to process ${logFile.getAbsolutePath}", e)
                failedCount += 1
            }
          }

          if (logFiles.lengthIs > 1) {
            logger.info(s"Processed $processedCount files successfully, $failedCount failed")
          }

          if (failedCount > 0) 1 else 0
      }
    }
  }

  private def outputCsv(records: CollectedRecords, writer: PrintWriter): Unit = {
    // Write CSV header
    writer.println(
      "record_type,scenario_name,group_hierarchy,request_name,status,start_timestamp,end_timestamp,response_time_ms,error_message,event_type,duration_ms,cumulated_response_time_ms,is_incoming"
    )

    // Output user records
    records.userRecords.foreach { userRecord =>
      val eventType = if (userRecord.event == MessageEvent.Start) "start" else "end"
      writer.println(s"user,${escapeCsv(userRecord.scenario)},,,,${userRecord.timestamp},,,,$eventType,,,")
    }

    // Output request records
    records.requestRecords.foreach { requestRecord =>
      val groupHierarchy = requestRecord.group.map(_.hierarchy.mkString("|")).getOrElse("")
      val status = if (requestRecord.status == OK) "OK" else "KO"
      val errorMessage = requestRecord.errorMessage.getOrElse("")
      val isIncoming = requestRecord.incoming.toString
      val endTimestamp = if (requestRecord.incoming) "" else (requestRecord.start + requestRecord.responseTime).toString
      writer.println(
        s"request,,${escapeCsv(groupHierarchy)},${escapeCsv(requestRecord.name)},$status,${requestRecord.start},$endTimestamp,${requestRecord.responseTime},${escapeCsv(errorMessage)},,,,$isIncoming"
      )
    }

    // Output group records
    records.groupRecords.foreach { groupRecord =>
      val groupHierarchy = groupRecord.group.hierarchy.mkString("|")
      val status = if (groupRecord.status == OK) "OK" else "KO"
      val endTimestamp = groupRecord.start + groupRecord.duration
      writer.println(
        s"group,,${escapeCsv(groupHierarchy)},,$status,${groupRecord.start},$endTimestamp,,,,${groupRecord.duration},${groupRecord.cumulatedResponseTime},"
      )
    }

    // Output error records
    records.errorRecords.foreach { errorRecord =>
      writer.println(s"error,,,,,${errorRecord.timestamp},,${escapeCsv(errorRecord.message)},,,,")
    }
  }

  // Emits percentiles matching Gatling's HTML report, computed the same way as
  // GeneralStatsBuffers in gatling-charts (AVLTreeDigest(100.0) + identical rounding).
  // One row per (group_hierarchy, request_name) and status bucket: OK, KO, ALL.
  private def outputPercentilesCsv(records: CollectedRecords, writer: PrintWriter): Unit = {
    writer.println("group_hierarchy,request_name,status,count,min,p50,p75,p95,p99,max")

    // Keyed by (groupHierarchy, requestName, statusLabel) where statusLabel is "OK", "KO", or "ALL".
    // LinkedHashMap preserves first-seen order so rows line up with how Gatling renders the HTML table.
    val digests = mutable.LinkedHashMap.empty[(String, String, String), (AVLTreeDigest, Long)]

    def record(key: (String, String, String), responseTime: Int): Unit = {
      val (digest, count) = digests.getOrElseUpdate(key, (new AVLTreeDigest(100.0), 0L))
      digest.add(responseTime.toDouble)
      digests.update(key, (digest, count + 1))
    }

    records.requestRecords.foreach { requestRecord =>
      // Match ResultsHolder.addRequestRecord: skip incoming records so HTML parity holds.
      if (!requestRecord.incoming) {
        val groupHierarchy = requestRecord.group.map(_.hierarchy.mkString("|")).getOrElse("")
        val statusLabel = if (requestRecord.status == OK) "OK" else "KO"
        record((groupHierarchy, requestRecord.name, statusLabel), requestRecord.responseTime)
        record((groupHierarchy, requestRecord.name, "ALL"), requestRecord.responseTime)
      }
    }

    digests.foreachEntry { case ((groupHierarchy, requestName, status), (digest, count)) =>
      writeRow(writer, groupHierarchy, requestName, status, count, digest)
    }
  }

  private def writeRow(writer: PrintWriter, groupHierarchy: String, requestName: String, status: String, count: Long, digest: AVLTreeDigest): Unit = {
    val min = digest.quantile(0).toInt
    val max = digest.quantile(1).toInt
    val p50 = math.round(digest.quantile(0.50)).toInt
    val p75 = math.round(digest.quantile(0.75)).toInt
    val p95 = math.round(digest.quantile(0.95)).toInt
    val p99 = math.round(digest.quantile(0.99)).toInt
    writer.println(
      s"${escapeCsv(groupHierarchy)},${escapeCsv(requestName)},$status,$count,$min,$p50,$p75,$p95,$p99,$max"
    )
  }

  private def escapeCsv(value: String): String =
    if (value.contains("\"") || value.contains(",") || value.contains("\n") || value.contains("|")) {
      "\"" + value.replace("\"", "\"\"") + "\""
    } else {
      value
    }
}
