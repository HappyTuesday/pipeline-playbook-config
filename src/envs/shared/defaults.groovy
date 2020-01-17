package envs.shared

import com.yit.deploy.core.dsl.parse.EnvironmentBaseScript
import groovy.transform.BaseScript
@BaseScript EnvironmentBaseScript $this

abstracted()

inherits "aspect.vars"
inherits "aspect.groups"