# AWS redis cluster test

This project is a test over the AWS redis cluster to verify its performance, scalability and resilience.

## Master failover experiment

The project contains a test called `TestRedis`. This test starts an in-memory redis cluster and runs the test simulation.

After `n` seconds in the simulation one of the master nodes is stopped, by default 20 seconds.

### Conclusion

The test shows the `lettuce` redis client with `autoreconnect` enabled is not able to recover from a `master-failover` scenario,
while `jedis` is able to continue by losing some messages (same behaviour if `lettuce` has `autoreconnect` disable).

### Running the test

The `TestRedis` starts a cluster of 9 nodes, with 3 primary and 6 secondary. Depending on the configuration the client
can be `Lettuce` or `Jedis`: in a loop the client will `set` and `get` a key.

```scala
import com.spaceape.test.Runner

val runner = new Runner(
  numberOfCallers = 1,
  duration = 2.minutes,
  operationInterval = 100.millis, // interval between calls
  host = host, // one of the node to start the client
  port = port,
  useJedis = false, // jedis if true, otherwise lettuce
  lettuceAutoReconnect = true // auto reconnect flag for lettuce client, ignored for jedis
)
runner.run()
```

### Understanding the result

While the simulation is running there are logs printed to check the state.

```
14:42:31.386 INFO  Caller - client [caller-1] running operation number [1120] (1)
14:42:32.419 INFO  Caller - client [caller-1] running operation number [1110]
14:42:33.002 INFO  Client - M[5ce][8079][OK], M[67b][8085][OK], M[7ba][8082][OK] (2)
14:42:33.004 INFO  ClusterInfo - M[5ce][8079][OK][2], M[67b][8085][OK][2], M[7ba][8082][OK][2] (3)
```

1. every 10 operations the client logs the status and remaining operations
2. lettuce only, the client prints the internal partitions data structure that summarises the cluster status
3. both clients, the cluster status

The cluster status log is made by `M` for master, `[5ce]` the first 3 letters of the node id, `[8079]` node port,
`[OK]` the node status (`OK`, `FAILING`, `FAILED`), (optional) `[2]` number of secondary nodes.

The following message will appear because one node has been forced to fail.

```
14:48:06.801 INFO  TestRedis - #########################################
14:48:06.802 INFO  TestRedis - #########################################
14:48:06.802 INFO  TestRedis - ## stop the master with port [8079]   ##
14:48:06.802 INFO  TestRedis - #########################################
14:48:06.802 INFO  TestRedis - #########################################
...
14:48:11.051 INFO  ClusterInfo - M[15b][8077][OK][1], M[3bc][8085][OK][2], M[5ce][8079][FAIL][0], M[905][8082][OK][2]
```

Eventually the `ClusterInfo` will detect the master failover and promote the secondary as primary node.

The successful result of a test will log the following lines.

```
14:53:25.017 INFO  Runner - results
14:53:25.020 INFO  Runner - successes 1188
14:53:25.020 INFO  Runner - failures 12
14:53:25.021 INFO  Runner - failure messages: Could not get a resource from the pool, CLUSTERDOWN The cluster is down
14:53:25.022 INFO  Runner - max failure period: 1200 milliseconds
14:53:25.022 INFO  Runner - average failure period: 1200 milliseconds
14:53:25.022 INFO  Runner - performance
14:53:25.024 INFO  Runner - performance (80 percentile): 1.0
14:53:25.024 INFO  Runner - performance (90 percentile): 1.0
14:53:25.024 INFO  Runner - performance (99 percentile): 2.0
14:53:25.024 INFO  Runner - performance (99.99 percentile): 10.0
```

The logs above (jedis) show 12 messages have been lost and the reason was due the cluster (expected).

Same result with Lettuce with auto reconnect disabled.

The test will timeout with `Lettuce` and auto reconnect enable.

### Considerations

The `Lettuce` client is able to successfully detect the new cluster topology after the failover but the command
seems to be stuck in a loop where it tries to reconnect to same node event if it has been marked by the cluster as "FAIL"
and a new primary node has been elected. 
