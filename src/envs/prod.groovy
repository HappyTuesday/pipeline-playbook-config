package envs

import com.yit.deploy.core.dsl.parse.EnvironmentBaseScript
import groovy.transform.BaseScript
@BaseScript EnvironmentBaseScript $this

inherits "shared.defaults"

group ("infrastructure") {
    host "172.10.1.1"
    host "172.10.1.2"
    host "172.10.1.3"
}