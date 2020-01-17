package playbooks.shared

import groovy.transform.BaseScript
import com.yit.deploy.core.dsl.parse.PlaybookBaseScript

@BaseScript PlaybookBaseScript $this

inherits "shared.base"

IS_INTEGRATED_PROJECT = true
INTEGRATED_PROJECT_FOLDER_NAME = variable {
    PROJECT_NAME
}

play ("copy-source-code") {
    localhost()

    task ("copy source code") {
        workspaceFilePath.deleteContents()
        String resourceFolder = "projects/$INTEGRATED_PROJECT_FOLDER_NAME"
        debug "load project files from resource folder: $resourceFolder"
        copyResourcesToFolder(resourceFolder, workspaceFilePath)
    }
}