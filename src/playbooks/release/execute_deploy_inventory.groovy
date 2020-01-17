package playbooks.release

import com.yit.deploy.core.exceptions.DeployException
import com.yit.deploy.core.exceptions.ExitException
import com.yit.deploy.core.function.Lambda
import com.yit.deploy.core.global.resource.Resources
import com.yit.deploy.core.parameters.inventory.DeployInventory
import com.yit.deploy.core.parameters.inventory.DeployItem
import com.yit.deploy.core.parameters.inventory.DeployPlan
import com.yit.deploy.core.parameters.inventory.DeployPlanService
import com.yit.deploy.core.parameters.inventory.InventoryChange
import com.yit.deploy.core.utils.GitUtils
import com.yit.deploy.core.utils.Utils
import com.yit.deploy.core.workflow.DependencyWorkflow
import groovy.transform.BaseScript
import com.yit.deploy.core.dsl.parse.PlaybookBaseScript
import hudson.AbortException

@BaseScript PlaybookBaseScript $this

inherits "shared.base"

PROJECT_BRANCH_KEY = "project_branch"
DEPLOY_INVENTORY_PARAMETER_NAME = "deployInventory"
DEFAULT_BRANCH_NAME = "master"

defaultScene (
        "execute-inventory"
)

DEFAULT_INVENTORY_NAME = abstractedVariable String
PREDEFINED_INVENTORIES = []

deployInventory = deployInventoryParameter(new DeployInventory()) options (
    defaultInventoryName: lazy {DEFAULT_INVENTORY_NAME},
    predefinedInventoryListJson: lazy {
        toJson(PREDEFINED_INVENTORIES)
    }
)

generatePlanForApiDeploy = closureVariable {
    DeployInventory inventory = deployInventory
    List<DeployItem> items = []

    int insertIndex = -1
    for (int i = 0; i < inventory.plans.size(); i++) {
        DeployPlan plan = inventory.plans.get(i)
        for (DeployItem t : plan.items) {
            if (t.project.sectionName == "dubbo-service" && t.projectName != "common-service" && t.verifyMessage != "OK") {
                List<String> tags = Lambda.intersect(["git-pull", "prepare-project", "maven-build"], t.tags)
                if (tags.empty) continue

                items << new DeployItem(
                    parameters: t.parameters + [only_deploy_api: (Object) "true"],
                    project: t.project,
                    projectName: t.projectName,
                    servers: t.servers,
                    skipTags: t.skipTags,
                    tags: tags,
                    dependencies: []
                )

                if (insertIndex < 0) {
                    insertIndex = i
                }
            }
        }
    }

    if (insertIndex >= 0) {
        DeployPlan oldPlan = inventory.plans.get(insertIndex)
        DeployPlan newPlan = new DeployPlan(
            description: "Plan for api deploy",
            items: items,
            reverseOrder: oldPlan.reverseOrder,
            confirmBeforeFinish: oldPlan.confirmBeforeFinish,
            parallel: oldPlan.parallel
        )

        inventory.plans.add(insertIndex, newPlan)
    }
}

generateBatches = closureVariable { DeployPlan plan ->
    plan.items.groupBy{
        it.project.jobOrder
    }.sort{
        plan.reverseOrder ? -it.key : it.key
    }.collect{
        it.value
    }.toList()
}

createDependencyWorkflow = closureVariable { DeployPlan plan, List<DeployItem> items ->
    def workflow = new DependencyWorkflow<DeployItem>(plan.getParallel() <= 0 ? Integer.MAX_VALUE : plan.getParallel())
    for (DeployItem item : items) {
        workflow.task(item.projectName, item) {
            executeClosure executeDeployItem as Closure, it
        }
    }
    for (DeployItem item : items) {
        for (String p : item.dependencies + item.project.dependencies) {
            if (items.any {it.projectName == p}) {
                if (plan.reverseOrder) {
                    workflow.depends p, item.projectName
                } else {
                    workflow.depends item.projectName, p
                }
            }
        }
    }
    return workflow
}

executePlan = closureVariable { DeployPlan plan, String planName ->
    List<List<DeployItem>> batches = executeClosure generateBatches as Closure, plan
    info "start to deploy $planName, in ${batches.size()} batch[es], description: ${plan.description}"

    Map<String, List<String>> parts = new HashMap<>()
    for (def project in plan.items.project) {
        def value = getProjectVarOrDefault("DEPLOY_PARTITION", project.projectName)
        if (!value) continue
        def ps = value instanceof Collection ? value : [value]
        for (def p in ps) {
            parts.computeIfAbsent(p as String, {new LinkedList<>()}) << project.projectName
        }
    }

    for (def p in parts.keySet()) {
        info "create deploy partition [$p] ${parts[p]}"
        Resources.deployPartitions.create(p, parts[p])
    }

    for (def j = 0; j < batches.size(); j++) {
        List<DeployItem> batch = batches[j]
        info "start batch #${j + 1}"
        executeClosure executeBatch as Closure, plan, batch
    }

    for (def p in parts.keySet()) {
        info "remove deploy partition [$p]"
        Resources.deployPartitions.remove(p)
    }
}

executeDeployItem = closureVariable { DeployItem item ->
    DeployInventory inventory = deployInventory
    for (def i = inventory.retries; i >= 0; i--) {
        try {
            Map<String, Object> parameters = item.parameters + [
                tags: item.tags,
                skip_tags: item.skipTags,
                servers: (Object) item.servers
            ]
            script.scheduleBuild(item.project.jobName, parameters, true, true)
            break
        } catch (ex) {
            warn "execute project $item.projectName failed: $ex"
            if (i > 0) {
                if (inventory.confirmBeforeRetry && !inventory.noInterrupt) {
                    try {
                        String choice = input("skip or retry project $item.projectName", "retry", "skip", "abort")
                        if (choice == "skip") {
                            skippedTasks << item
                            break
                        } else if (choice == "retry") {
                            continue
                        } else {
                            assert false
                        }
                    } catch (Exception e) {
                        failedTasks << item
                        throw e
                    }
                }
                info "retry project $item.projectName. left $i time(s)"
                if (inventory.autoAdjustBranch &&
                    i == inventory.retries &&
                    item.parameters.containsKey(PROJECT_BRANCH_KEY)) {

                    executeClosure adjustProjectBranch as Closure, item
                }
            } else if (inventory.ignoreFailure) {
                warn "ignore this failure. continue next step"
                failedTasks << item
                break
            } else {
                failedTasks << item
                throw ex
            }
        }
    }
}

adjustProjectBranch = closureVariable { DeployItem item ->
    String originBranch = item.parameters[PROJECT_BRANCH_KEY]
    if (originBranch == null || originBranch.empty) return
    List<String> branches
    try {
        branches = new GitUtils(script).listRemoteHeads(item.project.gitRepositoryUrl)
    } catch (Exception e) {
        warn "list remote branches for $item.projectName failed: $e"
        return
    }
    if (originBranch in branches) return

    if (!(DEFAULT_BRANCH_NAME in branches)) return
    try {
        executeClosure changeProjectBranchParameter as Closure, item
    } catch (Exception e) {
        warn e.message
        return
    }
    item.parameters[PROJECT_BRANCH_KEY] = DEFAULT_BRANCH_NAME
    info "project branch of $item.project.jobName has changed from $originBranch to $DEFAULT_BRANCH_NAME"
}

changeProjectBranchParameter = closureVariable { DeployItem item ->
    DeployPlanService service = Utils.getDeployPlanService(script.envvars.JOB_NAME, DEPLOY_INVENTORY_PARAMETER_NAME)

    DeployItem originItem = service.findDeployItem(item.projectName)
    if (originItem == null) {
        throw new Exception("could not find deploy item $item.projectName, adjust project branch failed")
    }
    if (originItem.parameters == null) {
        originItem.parameters = [:]
    }
    originItem.tags = null
    originItem.skipTags = null
    originItem.servers = null
    originItem.parameters[PROJECT_BRANCH_KEY] = DEFAULT_BRANCH_NAME

    try {
        service.updateDeployItem(item.projectName, originItem)
    } catch (Exception e) {
        throw new Exception("could not update deploy item $item.projectName, adjust project branch failed. detailed error: $e")
    }
}

executeBatch = closureVariable { DeployPlan plan, List<DeployItem> batch ->
    def workflow = executeClosure createDependencyWorkflow as Closure<DependencyWorkflow<DeployItem>>, plan, batch
    debug "workflow: \n$workflow"
    Map<String, Closure> threads = [:]
    for (DeployItem item : batch) {
        String projectName = item.projectName
        threads[projectName] = {
            workflow.executeWork(projectName)
        }
    }
    parallel threads
}

processFailedJobs = closureVariable {
    DeployInventory inventory = deployInventory
    List<String> failedJobNames = failedTasks.collect {it.project.jobName}
    List<String> skippedJobNames = skippedTasks.collect {it.project.jobName}

    if (failedJobNames.empty && skippedJobNames.empty) {
        return
    }

    String emailBody = "Execute deploy inventory failed in job ${script.envvars.JOB_NAME} ($script.absoluteUrl)." +
        " Failed jobs: \n" + failedJobNames.join('\n') + "\nSkipped jobs: \n" + skippedJobNames.join('\n')

    String emailSubject = "[Jenkins][$env.name] Execute Job ${script.envvars.JOB_NAME} Failed"

    if (script.currentUser.emailAddress == null) {
        for (DeployItem item in failedTasks) {
            List<String> users = []
            for (InventoryChange change in inventory.changes) {
                if (change.hasChanges("/plans/*/items/" + item.projectName)) {
                    users << change.user
                    if (users.size() > 0) break
                }
            }

            String jobName = item.project.jobName
            List<String> mailAddresses = users.collect {
                script.findDeployUser(it).emailAddress
            }.findAll {
                it != null
            }.unique()

            if (mailAddresses.empty) {
                warn "could not find any mail addresses to send the job [$jobName] execution failure email"
            } else if (!failedJobNames.empty) {
                mail {
                    to mailAddresses.join(',')
                    subject "[Jenkins][$env.name] Execute Job $jobName Failed"
                    body """\
                                execute job $item.project.jobName failed. please fix it.
                                if it is not your project, just ignore this email.
                            """.stripIndent()
                }
            }
        }

        if (!failedJobNames.empty) {
            mail {
                to inventory.notificationMails?.tokenize(',; \t\n')?.join(',')
                cc executionContext.getVariableOrDefault("DEPLOY_INVENTORY_FAILURE_NOTIFICATION_COPY_TO", "")
                subject emailSubject
                body emailBody
            }
        }
    } else if (!failedJobNames.empty) {
        mail {
            to script.currentUser.emailAddress
            cc executionContext.getVariableOrDefault("DEPLOY_INVENTORY_FAILURE_NOTIFICATION_COPY_TO", "")
            subject emailSubject
            body emailBody
        }
    }

    def message = "[SUMMARY]\nFailed Jobs: \n" + failedJobNames.join('\n') + "\\nnSkipped Jobs: \n" + skippedJobNames.join('\n')

    if (failedJobNames.empty) {
        info message
    } else {
        error message
    }
}

play ("execute-inventory") {
    task ("execute-inventory") {

        failedTasks = variable([])
        skippedTasks = variable([])

        DeployInventory inventory = deployInventory

        if (inventory.confirmBeforeExecute && !inventory.noInterrupt) {
            userConfirm("Executing deploy inventory $inventory.name")
        }

        if (env.prodEnv) {
            try {
                inventory.verifyForDeploy()
            } catch (DeployException e) {
                userConfirm("Verify for release failed: $e.message. Confirm to execute?")
            }
        }

        if (inventory.envName == "prod" || inventory.envName == "stage") {
            executeClosure generatePlanForApiDeploy as Closure
        }

        try {
            for (int i = 0; i < inventory.plans.size(); i++) {
                DeployPlan plan = inventory.plans[i]

                if (!plan.items.empty) {
                    executeClosure executePlan as Closure, plan, "Plan#" + (i + 1)
                }

                if (plan.confirmBeforeFinish && !inventory.noInterrupt) {
                    userConfirm("Finishing deploy Plan#" + (i + 1))
                }
            }
            info "[SUMMARY]\nAll projects are deployed successfully!"
        } finally {
            executeClosure processFailedJobs as Closure
        }
    }
}