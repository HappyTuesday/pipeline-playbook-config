package projects.aspect

import com.yit.deploy.core.dsl.parse.ProjectsBaseScript
import groovy.transform.BaseScript
@BaseScript ProjectsBaseScript $this

project ("abstract-zookeeper") {
    abstracted()
    playbook "shared.infrastructure"

    IMAGE_FULL_NAME = {"$DOCKER_REGISTRY/base/zookeeper:3.4.13-k8s"}
    SERVICE_PORT = 2181
    LEADER_PORT = 2889
    LEADER_ELECTION_PORT = 3889
    CLUSTER_MODE = lazy {TARGET_SERVERS.size() > 1}
    SERVER_JVMFLAGS = "-Xmx384M"
    SERVERS_URL = lazy {TARGET_SERVERS.collect {it + ':' + SERVICE_PORT}.join(',')}
    INTEGRATED_PROJECT_FOLDER_NAME = "abstract-zookeeper"
    PORTS_TO_WAIT = [{SERVICE_PORT}]
}