package com.spaceape

import java.util.concurrent.CompletionStage

import io.lettuce.core.cluster.api.async.RedisAdvancedClusterAsyncCommands

import scala.concurrent.{ExecutionContext, Future, Promise}

package object test {

  type LettuceCommands = RedisAdvancedClusterAsyncCommands[String,String]

  implicit class RedisFutureToFuture[T](redisFuture: CompletionStage[T]) {

    def asFuture(implicit executionContext: ExecutionContext): Future[T] = {
      val promise = Promise[T]()
      redisFuture.whenCompleteAsync((result: T, throwable: Throwable) => {
        if (throwable != null) promise.failure(throwable)
        else promise.success(result)
      })
      promise.future
    }

  }

}
