package io.github.salatmaster.direnv.rust

import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path

internal object ManagedRustToolchain {
    // tools: map of executable name to path of the directory they're in
    fun makeToolchainDir(project: Project, tools: Map<String, Path>): Path? {
        val projectRoot = project.basePath ?: return null

//        if(!tools.keys.containsAll(listOf("rustc", "cargo"))) TODO("error?")

        val toolchainHash = tools.hashCode().toHexString()

        val toolchainPath = Path.of(projectRoot, ".direnv/rust/toolchain/${toolchainHash}")
        if(Files.exists(toolchainPath)) return toolchainPath

        Files.createDirectories(toolchainPath)

        for((name, path) in tools.entries) {
            Files.createSymbolicLink(toolchainPath.resolve(name), path.resolve("bin").resolve(name))
        }

        return toolchainPath
    }
}
