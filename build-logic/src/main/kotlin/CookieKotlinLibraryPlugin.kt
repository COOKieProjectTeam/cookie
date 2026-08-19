import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaLibraryPlugin
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

class CookieKotlinLibraryPlugin : Plugin<Project> {
    override fun apply(project: Project) = with(project) {
        pluginManager.apply(JavaLibraryPlugin::class.java)
        pluginManager.apply("org.jetbrains.kotlin.jvm")

        extensions.configure<KotlinJvmProjectExtension> {
            jvmToolchain(25)
        }

        tasks.withType<KotlinCompilationTask<*>>().configureEach {
            compilerOptions {
                allWarningsAsErrors.set(true)
                freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
            }
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }
    }
}
