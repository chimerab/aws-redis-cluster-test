package com.spaceape.test

import com.spaceape.test.CallerContext._
import com.tdunning.math.stats.TDigest

object CallerContext {

  private val Compression = 10d

  def createDigest(): TDigest = TDigest.createDigest(Compression)

  case class RedisEntry(key: String, value: String)

}

case class CallerContext(operation: Int,
                         successes: Int = 0,
                         failures: Int = 0,
                         failureMessages: List[String] = Nil,
                         digest: TDigest = createDigest(),
                         entry: Option[RedisEntry] = None) {

  def addMetric(value: Double): Unit = digest.add(value)

  def incrementSuccess(entry: Option[RedisEntry]): CallerContext = copy(operation = operation - 1, successes = successes + 1, entry = entry)

  def incrementFailure(message: String): CallerContext = copy(operation = operation - 1, failures = failures + 1, failureMessages = message :: failureMessages)

}
