package com.spaceape.test

import org.scalatest.FeatureSpec

import scala.concurrent.duration._

class TestRedis extends FeatureSpec {

  val host = "<...>"

  scenario("create n redis clients that constantly interact to a redis cluster") {
    val runner = new Runner(numberOfCallers = 2, duration = 5.minutes, operationInterval = 200.millis, host = host)
    runner.run()
  }

}
