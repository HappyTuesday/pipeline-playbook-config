package playbooks.release

import com.yit.deploy.core.utils.Utils
import groovy.transform.BaseScript
import com.yit.deploy.core.dsl.parse.PlaybookBaseScript

@BaseScript PlaybookBaseScript $this

inherits "shared.base"

deploy_job_name = parameter {"yit-$env.name-deploy"} hidden()
deploy_plan_paramter_name = parameter "deployInventory" hidden()

DEPLOY_PLAN_SERVICE = { Utils.getDeployPlanService(deploy_job_name, deploy_plan_paramter_name) }
