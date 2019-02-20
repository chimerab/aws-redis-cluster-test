package com.spaceape.test

import java.util.concurrent.{Executors, TimeUnit}

import org.scalatest.{BeforeAndAfterAll, FeatureSpec}
import org.slf4j.LoggerFactory

import scala.concurrent.duration._

class TestRedis extends FeatureSpec with BeforeAndAfterAll {

  private val logger = LoggerFactory.getLogger(getClass)
  val localhost = "localhost"

  val host: String = localhost

  private val isLocal: Boolean = host == localhost
  def port: Int = if (isLocal) cluster.getSlavePorts.head else 6379

  val cluster = new RedisCluster(3, 2)

  override def beforeAll(): Unit = {
    if (isLocal) cluster.start()
  }

  scenario("create n redis clients that constantly interact to a redis cluster") {
    if (isLocal) {
      val scheduler = Executors.newScheduledThreadPool(1)
      scheduler.schedule(failover, 20L, TimeUnit.SECONDS)
    }
    val runner = new Runner(
      numberOfCallers = 1,
      duration = 2.minutes,
      operationInterval = 100.millis,
      host = host,
      port = port,
      useJedis = false,
      lettuceAutoReconnect = true
    )
    runner.run()
  }

  private val failover = new Runnable {
    override def run(): Unit = {
      val master = cluster.getMasters.head
      logger.info(s"#########################################")
      logger.info(s"#########################################")
      logger.info(s"## stop the master with port [${master.port}]   ##")
      logger.info(s"#########################################")
      logger.info(s"#########################################")
      master.stop()
    }
  }

  override def afterAll(): Unit = {
    if (isLocal) cluster.stop()
  }

}
