package com.spaceape.test

import java.util.UUID

import com.spaceape.test.CallerContext.RedisEntry
import io.lettuce.core.cluster.RedisClusterClient
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection
import org.slf4j.LoggerFactory

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

class Caller(id: String, client: RedisClusterClient, config: CallerConfig)(implicit executionContext: ExecutionContext) {

  private val logger = LoggerFactory.getLogger(getClass)

  private var connection: StatefulRedisClusterConnection[String, String] = _

  def start(): Future[CallerContext] = {
    val numberOfOperations = config.duration.toMillis / config.operationInterval.toMillis
    logger.info(s"client [$id] run for ${config.duration} with redis call every ${config.operationInterval} ($numberOfOperations calls)")
    connection = client.connect()
    val commands = connection.async()
    call(commands, CallerContext(numberOfOperations.toInt))
  }

  private def call(commands: Commands, context: CallerContext): Future[CallerContext] = {
    val operationNumber = context.operation
    if (operationNumber % 10 == 0) logger.info(s"client [$id] running operation number [$operationNumber]")
    if (operationNumber > 0) {
      operation(commands, context)
        .transform{
          case Success(entry) => Success(context.incrementSuccess(entry))
          case Failure(error) => Success(context.incrementFailure(error.getMessage))
        }
        .map(context => {
          Thread.sleep(config.operationInterval.toMillis)
          context
        })
        .flatMap(context => call(commands, context))
    } else {
      Future.successful(context)
    }
  }

  private def operation(commands: Commands, context: CallerContext): Future[Option[RedisEntry]] = {
    val startTime = System.currentTimeMillis()
    val result = context.entry match {
      case Some(entry) =>
        commands.get(entry.key).asFuture.flatMap { value =>
          if (value == entry.value) Future.successful(None)
          else Future.failed(new IllegalStateException("the entry is different"))
        }
      case None =>
        val entry = RedisEntry(random, random)
        commands.set(entry.key, entry.value).asFuture.map(_ => Some(entry))
    }
    result.andThen {
      case _ =>
        val duration = System.currentTimeMillis() - startTime
        context.addMetric(duration)
    }
  }

  private def random: String = UUID.randomUUID().toString

  def stop(): Unit = {
    connection.close()
  }

}
