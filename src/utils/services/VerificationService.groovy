package utils.services

import com.yit.deploy.core.config.ConfigProject
import com.yit.deploy.core.control.DeployService
import com.yit.deploy.core.dsl.execute.JobExecutionContext
import com.yit.deploy.core.exceptions.AbstractedVariableException
import com.yit.deploy.core.exceptions.MissingVariableException
import com.yit.deploy.core.model.Build
import com.yit.deploy.core.model.DeploySpec
import com.yit.deploy.core.model.Host
import com.yit.deploy.core.model.Job
import com.yit.deploy.core.model.PipelineScript
import com.yit.deploy.core.model.Project
import com.yit.deploy.core.model.Projects
import com.yit.deploy.core.storage.StorageConfig
import com.yit.deploy.core.support.AlgorithmSupport
import com.yit.deploy.core.variables.SimpleVariables
import groovy.json.JsonOutput

class VerificationService implements AlgorithmSupport {
    String env = 'prod'
    String project = '$'
    File targetPath = new File('out/prod-vars.json')

    def calculateVars() {
        assert this.env && this.targetPath && this.project

        def deployService = new DeployService(new ConfigProject("."), new StorageConfig())
        def modelTable = deployService.modelTable

        def map = [:]
        map.timestamp = new Date()

        def parent = modelTable.projects.get(this.project)

        Host.metaClass.getHostname {
            return name
        }

        Host.metaClass.getUname {
            "Linux"
        }

        Host.metaClass.getPublicIPAddress {
            return name
        }

        def jobs = (Collection<Job>) modelTable.jobs.getJobsInEnv(this.env).findAll { it.project.belongsTo(parent.projectName)}
        map.scheduled = jobs.findAll {
            it.schedule
        }.collectEntries {
            [(it.projectName): it.schedule]
        }

        Map<String, Map<String, Object>> projectsVars = [:]

        for (def job in jobs) {
            Map<String, Object> vars = [:]
            def script = new PipelineScript(this.env, job.projectName)
            Build build = new Build(job.jobName, new DeploySpec(), modelTable, deployService, script, null)
            def resolver = new JobExecutionContext(build)
            if (!job.plays.empty) {
                resolver = resolver.toPlay(job.playbook.plays[job.plays[0]])
                resolver = resolver.toHost(job.env.localHost, [job.env.localHost], new SimpleVariables())
                resolver.toTask(null)
            }

            resolver.setVariable("CURRENT_IMAGE_TAG", "ut")
            resolver.setVariable("IMAGE_TAG_TO_CREATE", "ut")

            for (def var in resolver.underlineVars.variables()) {
                def name = var.name().toString()
                if (name.contains('.')) continue
                def value
                try {
                    value = resolver.concreteVariable(var)
                } catch (Exception e) {
                    value = "error: $e.message"
                }

                vars[name] = value
            }

            projectsVars[job.projectName] = vars
        }

        extractAllCommonVars(projectsVars, modelTable.projects)

        map << projectsVars

        map.hostGroups = modelTable.getEnv(this.env).hostGroups.collectEntries {
            [
                (it.name): it.hosts.findAll {!it.retired}*.name
            ]
        }

        if (this.targetPath.exists()) {
            this.targetPath.renameTo(new File(this.targetPath.parent, "old-" + this.targetPath.name))
        }

        if (!this.targetPath.parentFile.exists()) {
            this.targetPath.parentFile.mkdir()
        }

        this.targetPath.write(JsonOutput.prettyPrint(toJson(map)))
        println "variables are written into ${this.targetPath.toURI()}"
    }

    static void extractAllCommonVars(Map<String, Map<String, Object>> projectsVars, Projects projects) {
        def close = new HashSet<>()
        for (def projectName in new ArrayList<>(projectsVars.keySet())) {
            for (def p in projects.get(projectName).descending) {
                if (!p.abstracted || !close.add(p.projectName)) {
                    continue
                }

                extractCommonVars(projectsVars, p.projectName, projects)
            }
        }
    }

    static void extractCommonVars(Map<String, Map<String, Object>> projectsVars, String parentProject, Projects projects) {
        Map<String, Object> commonVars = null
        List<String> ps = []
        for (def entry in projectsVars) {
            if (!projects.get(entry.key).belongsTo(parentProject)) {
                continue
            }

            ps << entry.key
            def vars = entry.value

            if (commonVars == null) {
                commonVars = new LinkedHashMap<>(vars)
                continue
            }

            for (def var in vars) {
                if (commonVars[var.key] != var.value) {
                    commonVars.remove(var.key)
                }
            }
        }

        if (commonVars == null) {
            return
        }

        for (def p in ps) {
            def vars = projectsVars[p]
            for (def key in commonVars.keySet()) {
                vars.remove(key)
            }
        }

        commonVars["_projects"] = ps
        projectsVars[parentProject] = commonVars
    }

    static void main(String[] args) {
        def service = new VerificationService()
        def method = null
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case '-e':
                    service.env = args[++i]
                    break
                case '-p':
                    service.project = args[++i]
                    break
                case '-f':
                    service.targetPath = new File(args[++i])
                    break
                case 'generateVars':
                    method = {service.calculateVars()}
                    break
            }
        }

        if (method) {
            method()
        }
    }
}
