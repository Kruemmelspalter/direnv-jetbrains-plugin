package io.github.salatmaster.direnv.rust

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import io.github.salatmaster.direnv.DirenvMachine
import io.github.salatmaster.direnv.DirenvService
import io.github.salatmaster.direnv.DirenvState
import io.github.salatmaster.direnv.DirenvStateListener
import io.github.salatmaster.direnv.toolchain.ToolchainCandidateResolver
import org.rust.cargo.project.model.CargoProjectsService
import org.rust.cargo.project.settings.RustProjectSettingsService
import org.rust.cargo.toolchain.RsLocalToolchain
import java.nio.file.Path

class RustDirenvStateListener(private val project: Project) : DirenvStateListener {
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


        // RustRover / the Rust plugin expects the bin/ directory where cargo and rustc are in directly and
        // not the parent of bin/ like other toolchain settings.
        var originalPaths: List<Path>? = null
        val toolchainCandidate = if (rustcCandidate == cargoCandidate) {
            cargoCandidate.resolve("bin")
        } else {
            originalPaths = listOf(rustcCandidate, cargoCandidate)
            ManagedRustToolchain.makeToolchainDir(
                project,
                mapOf("rustc" to rustcCandidate.resolve("bin"), "cargo" to cargoCandidate.resolve("bin"))
            ) ?: return
        }

        val currentToolchain = project.service<RustProjectSettingsService>().toolchain?.location
        if (currentToolchain == toolchainCandidate) return

        // Do not nag on every reload about a Toolchain the user already declined.
        // All previously suggested toolchains can still be loaded from the Notifications tab until it's cleared.
        if (lastSuggested == toolchainCandidate) return
        lastSuggested = toolchainCandidate

        suggest(project, toolchainCandidate, originalPaths)
    }

    // originalPaths is not null iff the toolchain is managed by the plugin in .direnv/rust/toolchain/<hash>
    // and then contains the paths that are linked into there
    private fun suggest(project: Project, toolchain: Path, originalPaths: List<Path>?) {
        val text: String = "direnv provides a Rust Toolchain at $toolchain" +
                (originalPaths?.let { ", generated from ${originalPaths.joinToString(", ")}" } ?: "")

        NotificationGroupManager.getInstance().getNotificationGroup("direnv")
            .createNotification(text, NotificationType.INFORMATION)
            .addAction(ApplyToolchainAction(project, toolchain))
            .notify(project)
    }

    private class ApplyToolchainAction(private val project: Project, private val toolchain: Path) :
        AnAction("Use This Toolchain") {

        private val log = Logger.getInstance(ApplyToolchainAction::class.java)

        override fun actionPerformed(e: AnActionEvent) {
            try {
                WriteAction.runAndWait<Throwable> {
                    val settingsService = project.service<RustProjectSettingsService>()

                    // TODO explicitPathToStdLib from $RUST_SRC_PATH ?
                    // has always worked out of the box so far
                    settingsService.modify { it.toolchain = RsLocalToolchain(toolchain) }

                    project.service<CargoProjectsService>().refreshAllProjects(false)
                }
            } catch (e: Throwable) {
                log.warn("Failed to apply Rust Toolchain provided by direnv", e)
            }
        }

    }
}