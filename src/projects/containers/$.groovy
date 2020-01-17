package projects.containers

import groovy.transform.BaseScript
import com.yit.deploy.core.dsl.parse.ProjectBaseScript
@BaseScript ProjectBaseScript $this

containerLabels([
    "all",
    lazy {project.projectName}
])

TARGET_SERVERS = lazy {
    job.servers
}