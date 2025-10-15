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

package io.gatling.logparser

import java.io.{ File, FileWriter, PrintWriter }

import io.gatling.charts.stats._
import io.gatling.commons.stats.{ KO, OK }
import io.gatling.core.config.GatlingConfiguration
import io.gatling.core.stats.message.MessageEvent
import io.gatling.logparser.cli.{ LogParserArgs, LogParserArgsParser }

import ch.qos.logback.classic.{ Level, Logger }
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

              // Create output file path next to the simulation.log
              val outputFile = new File(logFile.getParentFile, logFile.getName.replaceAll("\\.log$", ".csv"))
              logger.debug(s"Writing CSV output to: ${outputFile.getAbsolutePath}")

              val writer = new PrintWriter(new FileWriter(outputFile))
              try {
                outputCsv(records, writer)
                processedCount += 1
              } finally {
                writer.close()
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

  private def escapeCsv(value: String): String =
    if (value.contains("\"") || value.contains(",") || value.contains("\n") || value.contains("|")) {
      "\"" + value.replace("\"", "\"\"") + "\""
    } else {
      value
    }
}
