# AWS redis cluster test

This project is a test over the AWS redis cluster to verify its performance, scalability and resilience.

## How to build

The project use the maven to build the project.

> mvn clean package

This will generate an uberjar int hte target directory called `aws-redis-cluster-test.jar`.

## How to run it

To run the jar you need to provide the redis cluster hostname.

> java -jar target/aws-redis-cluster-test.jar host your-cluster.cache.amazonaws.com

This will run with default parameters that can be overridden by other command line params.

> java -jar target/aws-redis-cluster-test.jar host your-cluster.cache.amazonaws.com numberOfCallers 3 durationInMinutes 5 operationIntervalInMillis 200 

The `numberOfCallers` parameter is the number of parallel redis connection and operation (default 2).
The `durationInMinutes` parameter is the test duration (default 5).
The `operationIntervalInMillis` parameter is the operations interval toward redis (default 200).

As alternative it is possible to use the internal `TestRedis` class test (not configured with maven but runnable in IntelliJ).



