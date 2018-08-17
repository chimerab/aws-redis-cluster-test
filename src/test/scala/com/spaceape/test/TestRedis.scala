package com.spaceape.test

import java.util.concurrent.{Executors, TimeUnit}

import org.scalatest.{BeforeAndAfterAll, FeatureSpec}

import scala.concurrent.duration._

class TestRedis extends FeatureSpec with BeforeAndAfterAll {

  val host = "127.0.0.1"

  val cluster = new RedisCluster(3, 2)

  override def beforeAll(): Unit = {
    cluster.start()
  }

  scenario("create n redis clients that constantly interact to a redis cluster") {
    val scheduler = Executors.newScheduledThreadPool(1)
    scheduler.schedule(failover, 1L, TimeUnit.MINUTES)
    val runner = new Runner(numberOfCallers = 2, duration = 5.minutes, operationInterval = 200.millis, host = host, port = cluster.getSlavePorts.head)
    runner.run()
  }

  private val failover = new Runnable {
    override def run(): Unit = cluster.getMasters.head.stop()
  }

  override def afterAll(): Unit = {
    cluster.stop()
  }

}
