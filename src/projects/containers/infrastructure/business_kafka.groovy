package projects.containers.infrastructure

import groovy.transform.BaseScript
import com.yit.deploy.core.dsl.parse.ProjectsBaseScript
@BaseScript ProjectsBaseScript $this

project ("business-kafka-zk") {
    playbook "shared.infrastructure"
}

project ("business-kafka") {
    playbook "shared.infrastructure"
    depend "business-kafka-zookeeper"
}