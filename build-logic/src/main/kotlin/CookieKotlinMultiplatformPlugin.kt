import org.gradle.api.Plugin
import org.gradle.api.Project

class CookieKotlinMultiplatformPlugin : Plugin<Project> {
    override fun apply(project: Project) = with(project) {
        pluginManager.apply("org.jetbrains.kotlin.multiplatform")
        pluginManager.apply("org.jetbrains.kotlin.plugin.serialization")
    }
}
