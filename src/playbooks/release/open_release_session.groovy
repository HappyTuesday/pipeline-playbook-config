package playbooks.release

import com.yit.deploy.core.parameters.inventory.DeployInventory
import com.yit.deploy.core.parameters.inventory.DeployPlanService
import com.yit.deploy.core.utils.Utils
import groovy.transform.BaseScript
import com.yit.deploy.core.dsl.parse.PlaybookBaseScript

import java.text.SimpleDateFormat

@BaseScript PlaybookBaseScript $this

inherits "release.release_session"

deploy_session_name = parameter "" description "Release session name to open. Leave it empty to use current timestamp." order 200

defaultScene "open-release-session"

play ("open-release-session") {
    localhost()

    task ("open-release-session") {

        DeployPlanService service = DEPLOY_PLAN_SERVICE

        String sessionName = service.getActiveInventoryName()
        if (sessionName) {
            warn "Release session $sessionName is already opened"
            exitPlay()
        }
        sessionName = deploy_session_name ?: "release-" + new SimpleDateFormat("yyyy-MM-dd-HH-mm").format(new Date())
        DeployInventory inventory = service.getDeployInventory(sessionName)
        if (inventory.plans.size() > 1 || inventory.plans.size() == 1 && !inventory.plans[0].items.empty) {
            warn "Deploy inventory $sessionName is not empty"
            exitPlay()
        }
        if (inventory.sharedBy) {
            warn "Deploy inventory $sessionName is already shared by $inventory.sharedBy"
            exitPlay()
        }
        inventory.sharedBy = script.getCurrentUser().id
        service.saveDeployInventory(inventory)
        info "Deploy inventory $sessionName is now shared by $inventory.sharedBy."
        service.setActiveInventoryName(sessionName)
        info "Release session $sessionName is opened successfully under URL: ${Utils.getJenkinsJobBuildURL(deploy_job_name)}"
    }

    tag ("launch-verify-release-job") {
        task("launch-verify-release-job") {
            scheduleProject "verify-release"
        }
    }
}