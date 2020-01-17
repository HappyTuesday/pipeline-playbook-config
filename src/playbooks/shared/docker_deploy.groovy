package playbooks.shared

import com.yit.deploy.core.model.DeployBehavior
import groovy.transform.BaseScript
import com.yit.deploy.core.dsl.parse.PlaybookBaseScript

@BaseScript PlaybookBaseScript $this

inherits "shared.base"
inherits "shared.jinja_project"

CONTAINER_NAME = { PROJECT_NAME.replace '.', '-' }

PROJECT_DEPLOY_DIR = {
    PROJECT_ROOT + "/deploy"
}

DEPLOY_USER_HOME = {"/home/$DEPLOY_USER"}
JINJA_TEMPLATE_EXCLUDE_LIST << "deploy/docker-compose/" << "**/docker-compose.yml.j2"

TARGET_DEPLOY_HOSTGROUP = {PROJECT_NAME}

GLOBAL_DEPLOY_ROOT = {"$DEPLOY_USER_HOME/deploy"}
PROJECT_DOCKER_COMPOSE_DIR = variable {
    PROJECT_DEPLOY_DIR + "/docker-compose"
}
PROJECT_DOCKER_COMPOSE_FILE_PATH = variable {
    getFilePath(PROJECT_DOCKER_COMPOSE_DIR)
}
DEPLOY_ROOT = lazy { "$GLOBAL_DEPLOY_ROOT/$PROJECT_NAME" }

extendsPlay ("prepare-deploy") {
    search {TARGET_DEPLOY_HOSTGROUP}
    serial 1
    retries 1

    task ("sync files") {
        file {
            source PROJECT_DOCKER_COMPOSE_FILE_PATH
            target DEPLOY_ROOT
            fileOwner DEPLOY_USER
            withJinja()
        }
    }

    task ("pull images") {
        ssh {
            shell "docker-compose pull"
            pwd DEPLOY_ROOT
        }
    }
}

play ("shutdown-containers") {
    search {TARGET_DEPLOY_HOSTGROUP}
    serial 1
    retries 1

    includeRetiredHosts {
        when ({DEFAULT_DEPLOY_BEHAVIOR in [DeployBehavior.normal, DeployBehavior.absent]}) {
            tag ("shutdown") {
                task "before-shutdown-containers" reverse() tags "rolling-upgrade"

                task("shutdown-containers") {
                    ssh {
                        shell "docker-compose stop"
                        pwd DEPLOY_ROOT
                    }
                }

                tag ("remove-containers") {
                    task("remove-containers") {
                        ssh {
                            shell "docker-compose down -v"
                            pwd DEPLOY_ROOT
                        }
                    }
                }

                task "validate-shutdown-status" tags "rolling-upgrade"
                task "after-shutdown-containers" tags "rolling-upgrade"
            }
        }
    }
}

play ("startup-containers") {
    search {TARGET_DEPLOY_HOSTGROUP}
    serial 0.25
    retries 1

    tag("startup") {
        task "before-startup-containers" reverse() tags "rolling-upgrade"

        tag("create-containers") {
            task("create-containers") {
                ssh {
                    shell "docker-compose up -d"
                    pwd DEPLOY_ROOT
                }
            }
        }

        task("startup-containers") {
            ssh {
                shell "docker-compose start"
                pwd DEPLOY_ROOT
            }
        }

        task "validate-startup-status" tags "rolling-upgrade"
        task "after-startup-containers" tags "rolling-upgrade"
    }
}

extendsPlay ("docker-deploy") {
    search {TARGET_DEPLOY_HOSTGROUP}
    serial DOCKER_DEPLOY_SERIAL
    retries 1

    inherits "shutdown-containers"
    inherits "startup-containers"
}