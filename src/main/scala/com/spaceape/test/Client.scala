package com.spaceape.test

import com.spaceape.test.Client.Connection
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection
import io.lettuce.core.cluster.models.partitions.RedisClusterNode
import io.lettuce.core.cluster.{ClusterClientOptions, ClusterTopologyRefreshOptions, RedisClusterClient, StatefulRedisClusterConnectionImpl}
import io.lettuce.core.models.role.RedisInstance.Role
import org.slf4j.LoggerFactory
import redis.clients.jedis.{HostAndPort, Jedis, JedisCluster}

import scala.concurrent.{ExecutionContext, Future}
import scala.collection.JavaConverters._

trait Client {

  def connect(): Connection

}

object Client {

  private val logger = LoggerFactory.getLogger(classOf[Client])

  trait Connection {

    def commands(): Commands

    def close(): Unit

  }

  trait Commands {

    def get(key: String): Future[String]

    def set(key: String, entry: String): Future[String]

    def clusterNodes(): Future[Option[String]]

  }

  def jedis(host: String, port: Int = 6379): Client = {
    logger.info(s"create JEDIS redis cluster connecting to [$host:$port]")
    val cluster = new JedisCluster(new HostAndPort(host, port))
    val jedis = new Jedis(host, port)
    () => jedisConnection(cluster, jedis)
  }

  private def jedisConnection(cluster: JedisCluster, jedis: Jedis): Connection = new Connection {

    override def commands(): Commands = jedisCommands(cluster, jedis)

    override def close(): Unit = {}

  }

  private def jedisCommands(cluster: JedisCluster, jedis: Jedis): Commands = new Commands {

    override def get(key: String): Future[String] = try {
      Future.successful(cluster.get(key))
    } catch {
      case throwable: Throwable =>
        Future.failed(throwable)
    }

    override def set(key: String, entry: String): Future[String] = try {
      Future.successful(cluster.set(key, entry))
    } catch {
      case throwable: Throwable =>
        Future.failed(throwable)
    }

    override def clusterNodes(): Future[Option[String]] = try {
      Future.successful(Some(jedis.clusterNodes()))
    } catch {
      case throwable: Throwable =>
        Future.failed(throwable)
    }
  }

  private val refreshTopologyInterval = 10

  def lettuce(host: String, port: Int = 6379, autoReconnect: Boolean = true)(implicit executionContext: ExecutionContext): Client = {
    logger.info(s"create LETTUCE redis cluster connecting to [$host:$port] and topology refresh every [$refreshTopologyInterval] seconds")
    val client = RedisClusterClient.create(s"redis://$host:$port")
    // Set topology
    val topologyRefreshOptions = ClusterTopologyRefreshOptions.builder()
      .enablePeriodicRefresh(java.time.Duration.ofSeconds(refreshTopologyInterval))
      .enableAllAdaptiveRefreshTriggers()
      .build()
    client.setOptions(ClusterClientOptions.builder()
      .topologyRefreshOptions(topologyRefreshOptions)
      .autoReconnect(autoReconnect) // default value
      .build())
    () => lettuceConnection(client.connect())
  }

  private def lettuceConnection(lettuce: StatefulRedisClusterConnection[String, String])
                       (implicit executionContext: ExecutionContext): Connection = new Connection {

    override def commands(): Commands = lettuceCommands(lettuce.async())

    override def close(): Unit = lettuce.close()

  }

  private def lettuceCommands(lettuce: LettuceCommands)(implicit executionContext: ExecutionContext): Commands = new Commands {

    override def get(key: String): Future[String] = lettuce.get(key).asFuture

    override def set(key: String, entry: String): Future[String] = lettuce.set(key, entry).asFuture

    override def clusterNodes(): Future[Option[String]] = {
      val partitions = lettuce.getStatefulConnection.asInstanceOf[StatefulRedisClusterConnectionImpl[String, String]].getPartitions
      logger.info(partitions.getPartitions
        .asScala
        .filter(_.getRole == Role.MASTER)
        .sortBy(_.getNodeId)
        .map(nodeToString)
        .mkString(", ")
      )
      lettuce.clusterNodes().asFuture.map(Option.apply)
    }

    private def nodeToString(node: RedisClusterNode): String = {
      val role = node.getRole.name().head
      val port = node.getUri.getPort
      val fail = node.getFlags.contains(RedisClusterNode.NodeFlag.FAIL)
      val maybeFail = node.getFlags.contains(RedisClusterNode.NodeFlag.EVENTUAL_FAIL)
      val shortId: String = node.getNodeId.take(3)
      val status: String = if (fail && maybeFail) "FAILING" else if (fail) "FAIL" else "OK"
      s"$role[$shortId][$port][$status]"
    }

  }

}
