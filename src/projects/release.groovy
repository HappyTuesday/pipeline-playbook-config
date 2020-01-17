package projects

import groovy.transform.BaseScript
import com.yit.deploy.core.dsl.parse.ProjectsBaseScript
@BaseScript ProjectsBaseScript $this

$ {
    section "release"
    jobOrder 1
}

project ("open-release-session") {
    playbook "release.open_release_session"
}
project ("close-deploy-session") {
    playbook "release.close_release_session"
}
project ("verify-release") {
    playbook "release.verify_release"
}

defaults {
    playbook "release.execute_deploy_inventory"
} to {
    project ("deploy") {
        includeEnv "perf"
        DEFAULT_INVENTORY_NAME = ""

        PREDEFINED_INVENTORIES << [
            name: "default",
            noInterrupt: false,
            confirmBeforeExecute: false,
            confirmBeforeRetry: true,
            ignoreFailure: false
        ]

        override ("testenv") {
            PREDEFINED_INVENTORIES << [
                name : 'startup-environment',
                plans: [
                    [
                        extraTasksToSkip: ['verify'],
                        items      : [
                            [
                                projectName: 'restart-containers',
                                tags       : ["startup-containers"],
                                parameters : [
                                    container_filters: 'all'
                                ]
                            ]
                        ],
                        description: "startup all containers"
                    ]
                ]
            ] << [
                name : 'restart-environment',
                plans: [
                    [
                        extraTasksToSkip: ['verify'],
                        items      : [
                            [
                                projectName: 'restart-containers',
                                parameters : [
                                    container_filters: 'all'
                                ]
                            ]
                        ],
                        description: "shutdown and then restart all containers one by one."
                    ]
                ]
            ] << [
                name : 'shutdown-environment',
                plans: [
                    [
                        extraTasksToSkip: ['verify'],
                        items       : [
                            [
                                projectName: 'restart-containers',
                                tags       : ["shutdown-containers"],
                                parameters : [
                                    container_filters: 'all'
                                ]
                            ]
                        ],
                        reverseOrder: true,
                        description : "shutdown all containers"
                    ]
                ]
            ]
        }
    }
}