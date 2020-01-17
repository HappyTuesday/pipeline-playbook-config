package playbooks.shared

import com.yit.deploy.core.model.JinjaTemplate
import com.yit.deploy.core.utils.Utils
import hudson.FilePath
import groovy.transform.BaseScript
import com.yit.deploy.core.dsl.parse.PlaybookBaseScript
@BaseScript PlaybookBaseScript $this

inherits "shared.base"

JINJA_TEMPLATE_EXCLUDE_LIST = []

play ("prepare-project") {
    localhost()

    task ("generate-j2-templates") {
        JinjaTemplate engine = createJinjaTemplate()

        for (FilePath file in workspaceFilePath.list("**/*.j2", JINJA_TEMPLATE_EXCLUDE_LIST.join(','))) {
            String text = engine.render(file.readToString(), file.remote)
            FilePath file2 = new FilePath(file.channel, file.remote[0..<-".j2".length()])
            file2.write(text, Utils.DefaultCharset.toString())
        }
    }
}

play ("restore-project") {
    localhost()

    task ("delete-generated-files") {
        workspaceFilePath.list("**/*.j2", JINJA_TEMPLATE_EXCLUDE_LIST.join(',')).each {
            FilePath file =  getFilePath(it.remote[0..<-3])
            if (file.exists()) {
                file.delete()
            }
        }
    }
}