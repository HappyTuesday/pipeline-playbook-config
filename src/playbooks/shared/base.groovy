package playbooks.shared

import groovy.transform.BaseScript
import com.yit.deploy.core.dsl.parse.PlaybookBaseScript
@BaseScript PlaybookBaseScript $this

/**
 * this variable defines the name of the project which is currently deploying on.
 * some playbooks may process the same project, api-gateway and api-gateway-load-api, for instance.
 * NOTE: please do not confuse with another concept "project.name", which is just the name used on jenkins UI.
 * that is a part of the Jenkins job name.
 */
PROJECT_NAME = abstractedVariable String

PROJECT_ROOT = variable {workspace}