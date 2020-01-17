package playbooks.release

import com.yit.deploy.core.exceptions.DeployException
import com.yit.deploy.core.exceptions.ExitPlayException
import com.yit.deploy.core.model.Project
import com.yit.deploy.core.parameters.inventory.DeployInventory
import com.yit.deploy.core.parameters.inventory.DeployItem
import com.yit.deploy.core.parameters.inventory.DeployPlan
import com.yit.deploy.core.parameters.inventory.DeployPlanService
import com.yit.deploy.core.utils.GitUtils
import groovy.json.JsonOutput
import groovy.transform.BaseScript
import com.yit.deploy.core.dsl.parse.PlaybookBaseScript
import hudson.AbortException
import org.apache.tools.ant.ExitException

import java.util.concurrent.ConcurrentHashMap

@BaseScript PlaybookBaseScript $this

inherits "release.release_session"

defaultScene "verify-release-plan"

VERIFY_PROJECT_BRANCH = closureVariable { DeployItem item ->
    String branch = item.parameters.project_branch, repository = item.project.gitRepositoryUrl
    if (!branch || !repository) {
        return "OK"
    }

    try {
        GitUtils git = new GitUtils(script)

        List<String> branches = git.listRemoteHeads(repository)
        if (!branches.contains(branch)) {
            return "Error: Branch does not exist"
        }

        List<String> tags = branches.findAll { git.isReleaseTag(it) }
        if (!tags.contains(branch)) {
            return "OK"
        }

        List<String> sorted = git.sortReleaseTags(tags)
        if (sorted[0] != branch) {
            return "Release tag不是最新的tag(" + sorted[0] + ")"
        }

        return "OK"
    } catch (ExitException | AbortException | ExitPlayException | InterruptedException e) {
        throw e
    } catch (Exception e) {
        return "Error: $e"
    }
}

DEPLOY_ITEM_VERIFY_CACHE = new ConcurrentHashMap<>()

VERIFY_DEPLOY_ITEM_PREPARE = closureVariable { DeployPlan plan, DeployItem item ->

    if (!item.projectName) {
        return "OK" // do not verify search item
    }

    if ("git-pull" in item.tags && !item.parameters.project_branch) {
        return "Error: project-branch没有指定"
    }

    if (findJob(item.projectName) == null) {
        return "Error: 项目不存在"
    }

    boolean dependencyFailed = false
    for (def d in item.dependencies) {
        def dep = plan.findItem(d)
        if (!dep) {
            return "Error: 依赖项${d}不存在"
        }
        if (!dep.verifyMessage || dep.verifyMessage.startsWith("Error:")) {
            dependencyFailed = true
        }
    }

    Map<String, String> cache = DEPLOY_ITEM_VERIFY_CACHE

    def tagsToVerify = ["git-pull", "prepare-project", "maven-build", "maven-clean"]
    def finalTags = item.tags.findAll { it in tagsToVerify }

    if (finalTags.empty) {
        return "OK"
    }

    def cacheKey = JsonOutput.toJson([
            projectName: item.projectName,
            plays: finalTags,
            tasksToSkip: item.skipTags,
            userParameterSource: item.parameters
    ])

    // try to use cache at first
    def cacheValue = cache[cacheKey]
    if (cacheValue == "OK") {
        if ("git-pull" in item.tags) {
            return executeClosure(VERIFY_PROJECT_BRANCH as Closure<String>, item)
        }
        return cacheValue
    }

    return [
        dependencyFailed: dependencyFailed,
        closure: {
            info "开始验证项目：$item.projectName"
            String verifyMessage
            try {
                executeProject(item.projectName) {
                    plays finalTags
                    tasksToSkip item.skipTags
                    servers([])
                    userParameterSource item.parameters
                }

                verifyMessage = "OK"
            } catch (ExitException | AbortException | ExitPlayException | InterruptedException e) {
                throw e
            } catch (Exception e) {
                verifyMessage = "Error: " + e.getMessage()
            }

            cache[cacheKey] = verifyMessage

            if (verifyMessage == "OK") {
                if ("git-pull" in item.tags) {
                    verifyMessage = executeClosure VERIFY_PROJECT_BRANCH as Closure<String>, item
                }
            }

            info "项目($item.projectName)的验证结果为: $verifyMessage"

            return verifyMessage
        }
    ]
}

VERIFY_DEPLOY_ITEM_COMPLETE = closureVariable { DeployItem item, String previousVerifyMessage ->

    if (!item.projectName) return

    if (previousVerifyMessage && !item.verifyMessage) return

    if (!item.verifyMessage || item.verifyMessage.startsWith("Error:") || previousVerifyMessage != item.verifyMessage) {
        item.confirmedBy = null
    }
}

DEPLOY_ITEM_PREVIOUS_VERIFY_MESSAGES = new HashMap<>()

VERIFY_DEPLOY_PLAN = closureVariable { DeployInventory inventory, DeployPlan plan ->

    Map<String, String> previousVerifyMessages = DEPLOY_ITEM_PREVIOUS_VERIFY_MESSAGES

    for (def item in plan.items) {
        if (item.verifyMessage) {
            previousVerifyMessages[plan.id + "/" + item.id] = item.verifyMessage
        }
    }

    List<Map> ts = []
    for (def item in plan.items) {
        def result = executeClosure VERIFY_DEPLOY_ITEM_PREPARE as Closure, plan, item
        if (result instanceof Map) {
            ts << [
                item: item,
                dependencyFailed: result.dependencyFailed,
                closure: result.closure
            ]
        } else {
            item.verifyMessage = result as String
        }
    }

    List<Map> selected = []

    def notStarted = ts.findAll {
        !it.item.verifyMessage && !it.dependencyFailed
    }

    def len = 8
    def random = new Random()
    if (notStarted.size() < len) {
        selected.addAll(notStarted)
        def depFailed = ts.findAll {
            it.dependencyFailed
        }
        def failed = ts.findAll {
            it.item.verifyMessage && it.item.verifyMessage.startsWith("Error:")
        }
        def warned = ts.findAll {
            it.item.verifyMessage && !it.item.verifyMessage.startsWith("Error:")
        }
        int left = len - selected.size()
        int chooseFromDepFailed = Math.min(depFailed.size(), (int) Math.round((left / 2 > failed.size() ? left - failed.size() : left / 2).doubleValue()))
        int chooseFromFailed = Math.min(failed.size(), left - chooseFromDepFailed)
        for (int i = 0; i < chooseFromDepFailed; i++) {
            selected << depFailed.remove(random.nextInt(depFailed.size()))
        }
        for (int i = 0; i < chooseFromFailed; i++) {
            selected << failed.remove(random.nextInt(failed.size()))
        }
        while (selected.size() < len && !warned.empty) {
            selected << warned.remove(random.nextInt(warned.size()))
        }

        debug "${selected.size()} selected tasks consists from ${notStarted.size()} not started items, " +
            "${chooseFromDepFailed} dependency failed items, ${chooseFromFailed} failed items, and other warned."

    } else {
        while (selected.size() < len) {
            selected << notStarted.remove(random.nextInt(notStarted.size()))
        }
    }

    Map<String, Closure> tasks = selected.collectEntries {
        def item = it.item
        Closure closure = it.closure
        [
            (item.projectName): {
                item.verifyMessage = executeClosure closure
            }
        ]
    }

    if (!tasks.isEmpty()) {
        parallel tasks
    }

    for (def item in plan.items) {
        executeClosure VERIFY_DEPLOY_ITEM_COMPLETE as Closure, item, previousVerifyMessages[plan.id + "/" + item.id]
    }

    def notConfirmed = plan.items.count { !it.confirmedBy }

    if (notConfirmed == 0) {
        plan.verifyMessage = "OK"
    } else {
        plan.verifyMessage = "还有" + notConfirmed + "个项目没有确认"
    }

    return plan.items.any { !it.verifyMessage || it.verifyMessage.startsWith("Error:") }
}


play ("verify-release-plan") {
    localhost()

    task ("verify-release-plan") {

        DeployPlanService service = DEPLOY_PLAN_SERVICE

        def inventoryName = service.activeInventoryName
        if (!inventoryName) {
            error "currently no active inventory is set"
        }

        String lastChangeId = null
        DeployInventory last = null, inventory = null
        boolean continueVerify = false

        while (true) {

            service = DEPLOY_PLAN_SERVICE // update the instance, since job seed will change it

            if (service.activeInventoryName != inventoryName) {
                info "current active inventory is changed. exiting..."
                break
            }

            long version = last ? last.version + 1 : 0
            DeployInventory oldInventory = inventory
            inventory = service.httpPollDeployInventory(inventoryName,  version, continueVerify ? 10000 : 60000)

            if (inventory == null || inventory.version < version) {
                inventory = oldInventory // re-verify
            }

            if (!inventory.changes.empty && inventory.changes[0].id == lastChangeId && !continueVerify) { // skip what we have just committed
                continue
            }

            boolean needContinueVerify = false
            for (DeployPlan plan in inventory.plans) {
                needContinueVerify |= executeClosure VERIFY_DEPLOY_PLAN as Closure, inventory, plan
            }
            continueVerify = needContinueVerify

            if (service.activeInventoryName != inventoryName) {
                info "current active inventory is changed. exiting..."
                break
            }

            DeployInventory latest = service.getDeployInventory(inventoryName)
            if (latest.version != inventory.version) {
                warn "提交时发现部署清单已更新，10秒钟后重新校验"
                script.sleep 10
                continue
            }

            try {
                service.saveDeployInventory(inventory, lastChangeId = UUID.randomUUID().toString())
                last = inventory
                info "验证结果提交成功($lastChangeId)"
            } catch (DeployException e) {
                if (e.statusCode.code == 410) {
                    warn "提交时出现冲突，10秒钟后自动重试"
                    script.sleep 10
                } else {
                    warn "提交时出现错误：$e.message\n一分钟后自动重试"
                    script.sleep 60
                }
            } catch (ExitException | AbortException | ExitPlayException | InterruptedException e) {
                throw e
            } catch (Exception e) {
                warn "提交更新遇到错误：$e.message\n两分钟后自动重试"
                script.sleep 120
            }
        }
    }
}