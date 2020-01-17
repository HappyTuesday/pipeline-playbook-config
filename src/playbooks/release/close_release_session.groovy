package playbooks.release

import com.yit.deploy.core.parameters.inventory.DeployInventory
import com.yit.deploy.core.parameters.inventory.DeployPlanService
import groovy.transform.BaseScript
import com.yit.deploy.core.dsl.parse.PlaybookBaseScript

@BaseScript PlaybookBaseScript $this

inherits "release.release_session"

defaultScene "close-release-session"

play ("close-release-session") {
    localhost()

    task ("close-release-session") {
        DeployPlanService service = DEPLOY_PLAN_SERVICE
        def sessionName = service.getActiveInventoryName()
        if (!sessionName) {
            warn "Currently no release session is opened"
            return
        }
        DeployInventory inventory = service.getDeployInventory(sessionName)
        if (inventory.sharedBy) {
            info "Stop sharing of $sessionName launched by $inventory.sharedBy"
            inventory.sharedBy = null
            service.saveDeployInventory(inventory)
        }
        service.clearActiveInventoryName()
        info "Release session $sessionName is closed"
    }
}