import com.github.gradle.node.npm.task.NpmTask
import com.github.gradle.node.npm.task.NpxTask

plugins {
    alias(libs.plugins.node.gradle)
}

node {
    version.set("24.16.0")
    download.set(true)
    distBaseUrl.set(null as String?)
    npmInstallCommand.set("ci")
}

val webBuild = tasks.register<NpmTask>("webBuild") {
    dependsOn(tasks.npmInstall)
    npmCommand.set(listOf("run", "build"))
    inputs.file("package.json")
    inputs.file("package-lock.json")
    inputs.file("index.html")
    inputs.file("vite.config.ts")
    inputs.dir("src")
    inputs.dir("public")
    outputs.dir(layout.buildDirectory.dir("dist"))
}

tasks.register<NpmTask>("webCheck") {
    dependsOn(tasks.npmInstall)
    npmCommand.set(listOf("run", "check"))
}

tasks.register<NpmTask>("webE2e") {
    dependsOn(tasks.npmInstall)
    npmCommand.set(listOf("run", "test:e2e"))
}

tasks.register<NpxTask>("webBrowserInstall") {
    dependsOn(tasks.npmInstall)
    command.set("playwright")
    args.set(listOf("install", "--with-deps", "chromium", "firefox", "webkit"))
}
