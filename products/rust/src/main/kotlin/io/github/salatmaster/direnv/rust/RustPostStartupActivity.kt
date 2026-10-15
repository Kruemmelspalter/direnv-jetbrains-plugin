package io.github.salatmaster.direnv.rust

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import io.github.salatmaster.direnv.DirenvMachine
import io.github.salatmaster.direnv.DirenvService
import io.github.salatmaster.direnv.DirenvState
import io.github.salatmaster.direnv.DirenvStateListener
import io.github.salatmaster.direnv.toolchain.ToolchainCandidateResolver
import org.rust.cargo.project.model.CargoProjectsService
import org.rust.cargo.project.settings.RustProjectSettingsService
import org.rust.cargo.toolchain.RsLocalToolchain
import java.nio.file.Path

class RustPostStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.messageBus.connect().subscribe(
            DirenvStateListener.TOPIC,
            object : DirenvStateListener {
                private var lastSuggested: Path? = null

                override fun stateChanged(state: DirenvState) {
                    if (state !is DirenvState.Loaded) return

                    val workingDir = DirenvMachine.projectDir(project) ?: return
                    val environment = DirenvService.getInstance(project).cachedFor(workingDir) ?: return

                    // The environment was produced on the machine the project lives on, so the
                    // conventions its PATH follows are that machine's, not this one's.
                    val machine = DirenvMachine.toolchainMachine(project)

                    // directory that rustc is in
                    val rustcCandidate = ToolchainCandidateResolver.resolve(
                        environment.entries,
                        "HOME",
                        machine.executable("rustc"),
                        machine,
                    ) ?: return

                    // directory that cargo is in
                    val cargoCandidate = ToolchainCandidateResolver.resolve(
                        environment.entries,
                        "HOME",
                        machine.executable("cargo"),
                        machine
                    ) ?: return


                    val toolchainCandidate = if (rustcCandidate == cargoCandidate) {
                        cargoCandidate.resolve("bin")
                    } else {
                        // make custom toolchain from both paths?
                        ManagedRustToolchain.makeToolchainDir(
                            project,
                            mapOf("rustc" to rustcCandidate, "cargo" to cargoCandidate)
                        ) ?: return
                    }

                    val currentToolchain = project.service<RustProjectSettingsService>().toolchain?.location
                    if (currentToolchain == toolchainCandidate) return

                    // Do not nag on every reload about a JDK the user already declined.
                    if (lastSuggested == toolchainCandidate) return
                    lastSuggested = toolchainCandidate

                    suggest(project, toolchainCandidate)
                }
            },
        )
    }

    private fun suggest(project: Project, toolchain: Path) {
        NotificationGroupManager.getInstance().getNotificationGroup("direnv")
            .createNotification("direnv provides a Rust Toolchain at $toolchain", NotificationType.INFORMATION)
            .addAction(ApplyToolchainAction(project, toolchain))
            .notify(project)
    }
}

private class ApplyToolchainAction(private val project: Project, private val toolchain: Path) :
    AnAction("Use This Toolchain") {

    private val log = Logger.getInstance(ApplyToolchainAction::class.java)

    override fun actionPerformed(e: AnActionEvent) {
        try {
            WriteAction.runAndWait<Throwable> {
                val settingsService = project.service<RustProjectSettingsService>()

                // TODO explicitPathToStdLib
                settingsService.modify { it.toolchain = RsLocalToolchain(toolchain) }

                project.service<CargoProjectsService>().refreshAllProjects(false)
            }
        } catch (e: Throwable) {
            log.warn("Failed to apply Rust Toolchain provided by direnv", e)
        }
    }

}
