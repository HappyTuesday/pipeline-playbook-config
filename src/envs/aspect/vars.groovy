package envs.aspect

import com.yit.deploy.core.dsl.parse.EnvironmentBaseScript
import com.yit.deploy.core.model.MysqlDBConnection
import groovy.transform.BaseScript
@BaseScript EnvironmentBaseScript $this

abstracted()

ENABLE_SYNC_ENVIRONMENT_JOB_SCHEDULE = false

ENV = lazy {env.name}

JENKINS_USER = System.getProperty("user.name")
JENKINS_USER_HOME = System.getProperty("user.home")
DOCKER_REGISTRY = abstractedVariable String
DEPLOY_USER = "devops"