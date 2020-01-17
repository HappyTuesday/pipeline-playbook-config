package playbooks.shared

import groovy.transform.BaseScript
import com.yit.deploy.core.dsl.parse.PlaybookBaseScript
@BaseScript PlaybookBaseScript $this

inherits "shared.base"
inherits "shared.integrated_project"
inherits "shared.docker_container"
inherits "aspect.ports_check"

defaultScene (
    "copy-source-code",
    "prepare-deploy",
    "docker-deploy"
)