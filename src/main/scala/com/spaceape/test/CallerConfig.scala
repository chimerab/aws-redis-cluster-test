package com.spaceape.test

import scala.concurrent.duration.Duration

case class CallerConfig(numberOfCallers: Int, duration: Duration, operationInterval: Duration)
