package com.spaceape.test

import java.util.concurrent.{Executors, TimeUnit}

import io.lettuce.core.cluster.{ClusterClientOptions, ClusterTopologyRefreshOptions, RedisClusterClient}
import org.slf4j.LoggerFactory

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.Try

object Runner extends App {

  private val params = args
    .grouped(2)
    .filter(_.length == 2)
    .map(args => (args.head, args.last))
    .toMap

  private val host = params.getOrElse("host", throw new IllegalArgumentException("the 'host' should be defined"))
  private val numberOfCallers = params.get("numberOfCallers").flatMap(value => Try(value.toInt).toOption).getOrElse(2)
  private val duration = params.get("durationInMinutes").flatMap(value => Try(value.toInt).toOption).getOrElse(5).minutes
  private val operationInterval = params.get("operationIntervalInMillis").flatMap(value => Try(value.toInt).toOption).getOrElse(200).millis

  new Runner(numberOfCallers, duration, operationInterval, host).run()

}

class Runner(numberOfCallers: Int, duration: Duration, operationInterval: Duration, host: String, port: Int = 6379) {

  private val logger = LoggerFactory.getLogger(getClass)

  def run(): Unit = {
    logger.info("create configuration and thread pools")
    val config = CallerConfig(numberOfCallers, duration, operationInterval)
    implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(config.numberOfCallers + 1))

    logger.info("start scheduler to log cluster info")
    val scheduler = Executors.newScheduledThreadPool(1)
    val clusterInfo = new ClusterInfo(client)
    val schedule = scheduler.scheduleAtFixedRate(() => clusterInfo.logStatus(), 0, 2000, TimeUnit.MILLISECONDS)

    logger.info(s"create [${config.numberOfCallers}] callers")
    val callers = (1 to config.numberOfCallers).map(id => new Caller("caller-" + id, client, config))
    try {
      logger.info(s"start callers")
      val results = callers.map(_.start())

      logger.info(s"wait for result")
      val result = Await.result(Future.sequence(results), config.duration * 10)

      logger.info(s"results")
      logger.info("successes " + result.map(_.successes).sum)
      logger.info("failures " + result.map(_.failures).sum)
      logger.info("failure messages: " + result.flatMap(_.failureMessages).mkString(", "))

      logger.info(s"performance")
      val digest = CallerContext.createDigest()
      result.map(_.digest).foreach(digest.add)
      logger.info("performance (80 percentile): " + digest.quantile(0.8))
      logger.info("performance (90 percentile): " + digest.quantile(0.9))
      logger.info("performance (99 percentile): " + digest.quantile(0.99))
      logger.info("performance (99.99 percentile): " + digest.quantile(0.9999))
    } finally {

      logger.info(s"stop callers and schedule")
      callers.foreach(_.stop())
      schedule.cancel(true)
    }
  }

  private val refreshTopologyInterval = 10

  private lazy val client: RedisClusterClient = {
    logger.info(s"create redis cluster connecting to [$host:$port] and topology refresh every [$refreshTopologyInterval] seconds")
    val client = RedisClusterClient.create(s"redis://$host:$port")
    // Set topology
    val topologyRefreshOptions = ClusterTopologyRefreshOptions.builder()
      .enablePeriodicRefresh(java.time.Duration.ofSeconds(refreshTopologyInterval))
      .enableAllAdaptiveRefreshTriggers()
      .build()
    client.setOptions(ClusterClientOptions.builder()
      .topologyRefreshOptions(topologyRefreshOptions)
      .autoReconnect(true) // default value
      .build())
    client
  }

}
