package envs

import com.yit.deploy.core.dsl.parse.EnvironmentBaseScript
import groovy.transform.BaseScript
@BaseScript EnvironmentBaseScript $this

inherits "shared.testenv"

group ("infrastructure") {
    host "172.20.1.1"
    host "172.20.1.2"
    host "172.20.1.3"
}