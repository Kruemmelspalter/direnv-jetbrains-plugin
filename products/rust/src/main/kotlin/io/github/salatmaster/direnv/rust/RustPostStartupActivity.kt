package io.github.salatmaster.direnv.rust

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import io.github.salatmaster.direnv.DirenvStateListener

class RustPostStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.messageBus.connect().subscribe(
            DirenvStateListener.TOPIC,
            RustDirenvStateListener(project)
        )
    }
}
