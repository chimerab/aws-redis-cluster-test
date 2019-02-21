package com.spaceape.test

import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

import com.spaceape.test.CallerContext._
import com.tdunning.math.stats.TDigest

object CallerContext {

  private val Compression = 10d

  def createDigest(): TDigest = TDigest.createDigest(Compression)

  case class RedisEntry(key: String, value: String)

}

class CallerContext(val operation: AtomicInteger,
                    val successes: AtomicInteger = new AtomicInteger(0),
                    val failures: AtomicInteger = new AtomicInteger(0),
                    val failureMessages: AtomicReference[List[String]] = new AtomicReference[List[String]](Nil),
                    val digest: TDigest = createDigest(),
                    val entry: AtomicReference[Option[RedisEntry]] = new AtomicReference[Option[RedisEntry]](None)) {

  def addMetric(value: Double): Unit = digest.add(value)

  def incrementSuccess(entry: Option[RedisEntry]): Unit = {
    successes.incrementAndGet()
    this.entry.set(entry)
  }

  def incrementFailure(message: String): Unit = {
    failures.incrementAndGet()
    failureMessages.updateAndGet(messages => message :: messages)
    this.entry.set(None)
  }

}
