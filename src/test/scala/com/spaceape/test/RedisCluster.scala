package com.spaceape.test

import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

import io.lettuce.core.RedisClient
import io.lettuce.core.api.sync.RedisCommands
import org.slf4j.LoggerFactory
import redis.embedded.{RedisExecProvider, RedisServer, RedisServerBuilder}
import redis.embedded.util.OS
import com.spaceape.test.RedisCluster._

import scala.util.Try

class RedisCluster(numberOfShards: Int, numOfReplicates: Int) {

  private val logger = LoggerFactory.getLogger(getClass)

  private val nodeSlots = {
    val slotPerNode = MaxSlots / numberOfShards
    (1 to numberOfShards).foldLeft(List[NodeSlot]()) {
      case (slots, index) if index == numberOfShards => slots ++ List(NodeSlot((index - 1) * slotPerNode, MaxSlots - 1))
      case (slots, index) => slots ++ List(NodeSlot((index - 1) * slotPerNode, index * slotPerNode - 1))
    }
  }

  private val masters = (0 until numberOfShards).map { index =>
    val slot = nodeSlots(index)
    val slaves = (1 to numOfReplicates).map(_ => createSlaveNode(getRandomPort)).toList
    createMasterNode(getRandomPort, slot, slaves)
  }.toList

  private val mastersAndSlaves: List[ClusterNode] = masters.flatMap { master =>
    master :: master.slaves
  }

  def start(): Unit = {
    val clusterSize = mastersAndSlaves.size
    logger.info(s"start redis cluster with [$clusterSize] nodes")
    mastersAndSlaves.foreach(_.start())
    logger.info(s"allocate cluster slots to [${masters.size}] master nodes")
    val mastersWithNodes = masters.map(_.attachSlotAndBecomeAMaster())
    logger.info(s"all [$clusterSize] nodes join the cluster")
    mastersAndSlaves.indices.foreach { index =>
      val serverA = mastersAndSlaves(index)
      val serverB = mastersAndSlaves((index + 1) % mastersAndSlaves.size)
      serverA.meet(serverB)
    }
    logger.info("wait until the cluster is active ...")
    val result = waitUntilClusterIsActive(masters.head, maxNumberOfRetries = 3)
    if (result) {
      logger.info("cluster is active")
      logger.info("add slaves as replication of the masters")
      mastersWithNodes.foreach(_.replication())
      logger.info("Waiting for slaves to be in topology")
      mastersWithNodes.foreach(waitUntilSlavesAreInTopology(maxNumberOfRetries = 3))
    } else {
      logger.error("cluster cannot be activated")
      stop()
    }
  }

  private def waitUntilClusterIsActive(node: ClusterNode, maxNumberOfRetries: Int): Boolean = {
    if (maxNumberOfRetries == 0) false
    else if (node.isClusterActive) true
    else {
      Thread.sleep(1000)
      waitUntilClusterIsActive(node, maxNumberOfRetries - 1)
    }
  }

  def waitUntilSlavesAreInTopology(maxNumberOfRetries: Int)(node: MasterNode): Boolean = {
    if (maxNumberOfRetries == 0) false
    else if (node.hasEnoughSlaves) true
    else {
      Thread.sleep(1000)
      waitUntilSlavesAreInTopology(maxNumberOfRetries - 1)(node)
    }
  }

  def stop(): Unit = {
    logger.info("stop redis cluster")
    mastersAndSlaves.foreach(_.stop())
  }

  def getMasterPorts: List[Int] = masters.map(_.port)

  def getSlavePorts: List[Int] = masters.flatMap(_.slaves.map(_.port))

  def getMasters: List[MasterNode] = masters

}

object RedisCluster {

  private val MaxSlots = 16384

  val port = new AtomicInteger(8077)

  def getRandomPort: Int = port.getAndIncrement()

//  def getRandomPort: Int = {
//    var socket: ServerSocket = null
//    try {
//      socket = new ServerSocket(0)
//      socket.setReuseAddress(true)
//      socket.getLocalPort
//    } catch {
//      case e: IOException =>
//        throw new IllegalStateException("Impossible to find a free port", e)
//    } finally {
//      Try { socket.close() }
//    }
//  }

  sealed abstract class ClusterNode(val port: Int, server: RedisServer) {

    protected def exec[T](operation: (RedisCommands[String, String]) => T): T = {
      val client = RedisClient.create(s"redis://127.0.0.1:$port")
      val connection = client.connect()
      try {
        operation(connection.sync())
      } finally {
        connection.close()
        client.shutdown()
      }
    }

    def meet(node: ClusterNode): Unit = exec { commands =>
      commands.clusterMeet("127.0.0.1", node.port)
    }

    def isClusterActive: Boolean = exec { commands =>
      commands.clusterInfo().split("\r\n").head.split(":")(1) == "ok"
    }

    def start(): Unit = server.start()

    def stop(): Unit = server.stop()

  }

  case class MasterNode(override val port: Int,
                        server: RedisServer,
                        slot: NodeSlot,
                        slaves: List[SlaveNode],
                        masterId: Option[String] = None) extends ClusterNode(port, server) {

    def attachSlotAndBecomeAMaster(): MasterNode = {
      val masterId = exec { commands =>
        // Add slot to become a master
        commands.clusterAddSlots(slot.slots: _*)
        // Get the master id
        commands.clusterNodes().split("\n").toList
          .filter(line => line.contains("self"))
          .map { line =>
            line.split(" ", 2) match {
              case Array(id, _) => id
            }
          }.headOption
      }
      copy(masterId = masterId)
    }

    def replication(): Unit = masterId.foreach { id =>
      slaves.foreach(_.replicate(id))
    }

    def hasEnoughSlaves: Boolean = masterId.exists { id =>
      exec { commands =>
        commands.clusterSlaves(id).size == slaves.size
      }
    }

  }

  case class SlaveNode(override val port: Int,
                       server: RedisServer) extends ClusterNode(port, server) {

    def replicate(masterNodeId: String): Unit = exec { commands =>
      commands.clusterReplicate(masterNodeId)
    }

  }

  case class NodeSlot(first: Int, last: Int) {
    val slots: Array[Int] = (first to last).toArray
  }

  def createMasterNode(port: Int, slot: NodeSlot, slaves: List[SlaveNode]): MasterNode = {
    MasterNode(port, createServer(port), slot, slaves)
  }

  def createSlaveNode(port: Int): SlaveNode = {
    SlaveNode(port, createServer(port))
  }

  private val redisExecProvider = RedisExecProvider.defaultProvider()
    .`override`(OS.MAC_OS_X, "redis-server-3.2.12.app")
    .`override`(OS.WINDOWS, "redis-server-3.0.504.exe")
    .`override`(OS.UNIX, "redis-server-3.2.12")

  private def createServer(port: Int): RedisServer = {
    new RedisServerBuilder()
      .redisExecProvider(redisExecProvider)
      .setting("cluster-enabled yes")
      .setting("cluster-config-file nodes-" + port + ".conf")
      .setting("cluster-node-timeout 2000")
      .setting("appendonly yes")
      .setting("dbfilename dump-" + port + ".rdb")
      .setting("bind 127.0.0.1")
      .port(port)
      .build()
  }

}
