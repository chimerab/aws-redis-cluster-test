package com.spaceape.test

import io.lettuce.core.cluster.RedisClusterClient
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContext

class ClusterInfo(client: RedisClusterClient)(implicit executionContext: ExecutionContext) {

  private val logger = LoggerFactory.getLogger(getClass)

  private val commands = client.connect().async()

  def logStatus(): Unit = {
    commands.clusterNodes()
      .asFuture
      .map(_.split("\n").toList)
      .map( _.map(_.split(" ", 5) match {
        case Array(id, hostname, flags, master, _) => Node(id, hostname, flags, master)
      }))
      .map(convert)
      .foreach(logger.info)
  }

  case class Node(id: String, hostname: String, flags: String, master: String, slaves: List[Node] = Nil) {

    val isMaster: Boolean = flags.contains("master")
    val isSlave: Boolean = flags.contains("slave")
    val fail: Boolean = flags.contains("fail")
    val maybeFail: Boolean = flags.contains("fail?")
    val status: String = if (fail && maybeFail) "FAILING" else if (fail) "FAIL" else "OK"
    val shortId: String = id.take(3)

    def asString: String = s"master-$shortId[$status][${slaves.size}]"

  }

  private def convert(nodes: List[Node]): String = {
    val groupByMasterId = nodes.groupBy(node => if (node.isMaster) node.id else node.master)
    groupByMasterId.map { case (masterId, shard) =>
      val master = nodes.find(_.id == masterId).get
      master.copy(slaves = shard.filter(_.isSlave))
    }.toList
      .map(_.asString)
      .mkString(", ")
  }
}
