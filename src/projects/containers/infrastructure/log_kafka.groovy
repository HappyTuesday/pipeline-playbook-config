package projects.containers.infrastructure

import groovy.transform.BaseScript
import com.yit.deploy.core.dsl.parse.ProjectsBaseScript
@BaseScript ProjectsBaseScript $this

project ("log-kafka-zk") {
    playbook "shared.infrastructure"
}

project ("log-kafka") {
    playbook "shared.infrastructure"
    depend "business-kafka-zookeeper"

    DEFAULT_REPLICATION_FACTOR = 1
}