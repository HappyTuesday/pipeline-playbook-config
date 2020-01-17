package projects.aspect

import com.yit.deploy.core.dsl.parse.ProjectsBaseScript
import groovy.transform.BaseScript
@BaseScript ProjectsBaseScript $this

project ("abstract-kafka") {
    abstracted()
    playbook "shared.infrastructure"

    IMAGE_FULL_NAME = { "$DOCKER_REGISTRY/base/kafka:1.1.0" }
    INTEGRATED_PROJECT_FOLDER_NAME = "abstract-kafka"
    ZOOKEEPER_SERVERS_URL = lazy { getVariable "SERVERS_URL", "${PROJECT_NAME}-zk" }
    PORTS_TO_WAIT = [{SERVICE_PORT}]

    SERVICE_PORT = 9092
    JMX_PORT = 1099
    BOOTSTRAP_SERVERS_URL = { TARGET_SERVERS.collect {it + ':' + SERVICE_PORT}.join(',') }
    HEAP_OPTS = "-Xmx512m"
    LOG_RETENTION_BYTES = 2L * 1024 * 1024 * 1024 // 2GB
    LOG_RETENTION_HOURS = 24
    PARTITIONS = 3
    OFFSET_REPLICATION_FACTOR = { TARGET_SERVERS.size() }
    PRODUCER_MAX_BYTES = "1m"
    NUM_NETWORK_THREADS = 3
    NUM_IO_THREADS = 8
    TRANSACTION_STATE_LOG_MIN_ISR = 1
    LOG_FLUSH_INTERVAL_MESSAGES = 50000
    LOG_FLUSH_INTERVAL_MS = 5000
    DEFAULT_REPLICATION_FACTOR = { TARGET_SERVERS.size() }
    MESSAGE_MAX_BYTES = 5242880
    GROUP_INITIAL_REBALANCE_DELAY_MS = 3000
    NUM_REPLICA_FETCHERS = 1
    REPLICA_FETCH_MAX_BYTES = 1048576
    GROUP_MAX_SESSION_TIMEOUT_MS = 1800000L
}