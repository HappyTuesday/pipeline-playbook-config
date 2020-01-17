package playbooks.aspect

import groovy.transform.BaseScript
import com.yit.deploy.core.dsl.parse.PlaybookBaseScript

@BaseScript PlaybookBaseScript $this

inherits "shared.docker_container"

PORTS_TO_WAIT = []

extendsPlay ("docker-deploy") {

    task("validate-shutdown-status/wait-for-port-to-close") {
        for (int port in PORTS_TO_WAIT) {
            waitFor {
                waitForPort(currentHost, port, true)
            }
        }
    } when { !PORTS_TO_WAIT.empty && !playbookParams.ENABLE_K8S_DEPLOY }

    task("validate-startup-status/wait-for-port-to-open") {
        for (int port in PORTS_TO_WAIT) {
            waitFor {
                waitForPort(currentHost, port)
            }
        }
    } when { !PORTS_TO_WAIT.empty && !playbookParams.ENABLE_K8S_DEPLOY }
}