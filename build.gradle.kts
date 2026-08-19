import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.openapitools.generator.gradle.plugin.tasks.GenerateTask
import org.openapitools.generator.gradle.plugin.tasks.ValidateTask
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.nio.file.Files

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("org.yaml:snakeyaml:2.4")
    }
}

plugins {
    id("org.openapi.generator") version "7.24.0"
}

group = "com.cookie"
version = "0.1.0-SNAPSHOT"

allprojects {
    group = rootProject.group
    version = rootProject.version
}

@CacheableTask
abstract class BundlePublicOpenApiTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val sourceSpecs: ConfigurableFileCollection = project.objects.fileCollection()

    @get:OutputFile
    abstract val outputSpec: RegularFileProperty

    @get:Input
    abstract val bundleVersion: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val architectureModel: RegularFileProperty

    @TaskAction
    fun bundle() {
        val sources = sourceSpecs.files.sortedBy { it.name }
        require(sources.isNotEmpty()) { "At least one active public service OpenAPI contract is required" }

        val yaml = Yaml(SafeConstructor(LoaderOptions()))
        val architecture = stringMap(
            yaml.load<Any>(architectureModel.get().asFile.readText()),
            architectureModel.get().asFile.path,
        )
        val tagsByService = listValue(architecture["services"], "architecture services").associate { serviceValue ->
            val service = stringMap(serviceValue, "architecture service")
            val serviceId = service["id"]?.toString() ?: error("Architecture service id is required")
            val ownedTags = listValue(service["openapi_tags"], "architecture service $serviceId openapi_tags")
                .map { it.toString() }
                .toSet()
            serviceId to ownedTags
        }
        val documents = sources.map { source ->
            source to stringMap(yaml.load<Any>(source.readText()), source.path)
        }
        val openApiVersions = documents.map { (_, document) -> document["openapi"] }.distinct()
        require(openApiVersions.size == 1) { "Public service contracts must use one OpenAPI version: $openApiVersions" }

        documents.forEach { (source, document) ->
            require(stringMap(document["info"], "${source.path} info")["version"] != null) {
                "${source.path} info.version is required"
            }
        }

        val paths = linkedMapOf<String, Any?>()
        val tags = linkedMapOf<String, Any?>()
        val servers = linkedMapOf<String, Any?>()
        val components = linkedMapOf<String, LinkedHashMap<String, Any?>>()
        val securityDefaults = mutableListOf<Any?>()
        val operationOwners = linkedMapOf<String, String>()

        documents.forEach { (source, document) ->
            val serviceId = source.nameWithoutExtension
            val ownedTags = tagsByService[serviceId]
                ?: error("Active public contract ${source.name} has no service in architecture model")
            val declaredTags = listValue(document["tags"], "${source.path} tags").map { tagValue ->
                val tag = stringMap(tagValue, "${source.path} tag")
                tag["name"]?.toString() ?: error("${source.path} tag.name is required")
            }.toSet()
            require(declaredTags.isNotEmpty() && declaredTags.all(ownedTags::contains)) {
                "${source.name} declares tags $declaredTags but service $serviceId owns $ownedTags"
            }
            stringMap(document["paths"], "${source.path} paths").forEach { (route, pathItem) ->
                require(paths.putIfAbsent(route, pathItem) == null) {
                    "Public route $route is owned by more than one service contract"
                }
                stringMap(pathItem, "${source.path} path $route")
                    .filterKeys(HTTP_METHODS::contains)
                    .forEach { (method, operationValue) ->
                        val operation = stringMap(operationValue, "${source.path} $method $route")
                        val operationId = operation["operationId"]?.toString()
                            ?: error("${source.path} $method $route requires operationId")
                        val operationTags = listValue(
                            operation["tags"],
                            "${source.path} $method $route tags",
                        ).map { it.toString() }.toSet()
                        require(operationTags.isNotEmpty() && operationTags.all(ownedTags::contains)) {
                            "$method $route uses tags $operationTags but service $serviceId owns $ownedTags"
                        }
                        val previousOwner = operationOwners.putIfAbsent(operationId, "$method $route")
                        require(previousOwner == null) {
                            "Duplicate operationId $operationId at $previousOwner and $method $route"
                        }
                    }
            }
            listValue(document["tags"], "${source.path} tags").forEach { tagValue ->
                val tag = stringMap(tagValue, "${source.path} tag")
                val name = tag["name"]?.toString() ?: error("${source.path} tag.name is required")
                mergeNamed(tags, name, tag, "tag")
            }
            listValue(document["servers"], "${source.path} servers").forEach { serverValue ->
                val server = stringMap(serverValue, "${source.path} server")
                val url = server["url"]?.toString() ?: error("${source.path} server.url is required")
                mergeNamed(servers, url, server, "server")
            }
            document["components"]?.let { componentsValue ->
                stringMap(componentsValue, "${source.path} components").forEach { (category, entriesValue) ->
                    val target = components.getOrPut(category) { linkedMapOf() }
                    stringMap(entriesValue, "${source.path} components.$category").forEach { (name, value) ->
                        mergeNamed(target, name, value, "component $category")
                    }
                }
            }
            document["security"]?.let(securityDefaults::add)
        }

        require(securityDefaults.distinct().size <= 1) {
            "Public service contracts declare conflicting top-level security defaults; use operation-level security"
        }

        val bundled = linkedMapOf<String, Any?>(
            "openapi" to openApiVersions.single(),
            "info" to linkedMapOf(
                "title" to "COOKie Public API",
                "version" to bundleVersion.get(),
                "description" to "Generated from active per-service public OpenAPI contracts.",
            ),
            "x-service-contracts" to sources.map { "contracts/openapi/public/${it.name}" },
        )
        if (servers.isNotEmpty()) bundled["servers"] = servers.values.toList()
        if (tags.isNotEmpty()) bundled["tags"] = tags.values.toList()
        bundled["paths"] = paths
        securityDefaults.singleOrNull()?.let { bundled["security"] = it }
        if (components.isNotEmpty()) bundled["components"] = components

        val options = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            isPrettyFlow = true
            indent = 2
            indicatorIndent = 0
            width = 160
            splitLines = false
        }
        val output = outputSpec.get().asFile.toPath()
        Files.createDirectories(output.parent)
        Files.writeString(output, Yaml(options).dump(bundled))
    }

    private fun stringMap(value: Any?, context: String): LinkedHashMap<String, Any?> {
        val source = value as? Map<*, *> ?: error("$context must be a mapping")
        return source.entries.associateTo(linkedMapOf()) { (key, nestedValue) ->
            (key as? String ?: error("$context contains a non-string key")) to nestedValue
        }
    }

    private fun listValue(value: Any?, context: String): List<Any?> = when (value) {
        null -> emptyList()
        is List<*> -> value
        else -> error("$context must be a list")
    }

    private fun mergeNamed(target: MutableMap<String, Any?>, name: String, value: Any?, kind: String) {
        val existing = target.putIfAbsent(name, value)
        require(existing == null || existing == value) { "Conflicting public $kind named $name" }
    }

    companion object {
        private val HTTP_METHODS = setOf("get", "put", "post", "delete", "options", "head", "patch", "trace")
    }
}

val bundledPublicSpec = layout.buildDirectory.file("generated/openapi/bundled/public.yaml")
val bundlePublicOpenApi = tasks.register<BundlePublicOpenApiTask>("bundlePublicOpenApi") {
    group = "openapi tools"
    description = "Bundle active per-service public OpenAPI contracts."
    sourceSpecs.from(fileTree("contracts/openapi/public") { include("*.yaml") })
    outputSpec.set(bundledPublicSpec)
    bundleVersion.set(version.toString())
    architectureModel.set(layout.projectDirectory.file("docs/architecture/model/services.yaml"))
}

val validateBundledPublicOpenApi = tasks.register<ValidateTask>("validateBundledPublicOpenApi") {
    group = "verification"
    description = "Validate the generated public OpenAPI bundle."
    dependsOn(bundlePublicOpenApi)
    inputSpec.set(bundledPublicSpec)
}

tasks.register<GenerateTask>("generateKmpPublicClient") {
    group = "openapi tools"
    description = "Generate the KMP client from implemented public service contracts."
    dependsOn(validateBundledPublicOpenApi)
    generatorName.set("kotlin")
    library.set("multiplatform")
    cleanupOutput.set(true)
    inputSpec.set(bundledPublicSpec)
    outputDir.set(layout.projectDirectory.dir("apps/mobile/shared/build/generated/openapi").asFile.absolutePath)
    apiPackage.set("com.cookie.mobile.generated.api")
    modelPackage.set("com.cookie.mobile.generated.model")
    packageName.set("com.cookie.mobile.generated")
    configOptions.set(
        mapOf(
            "dateLibrary" to "kotlinx-datetime",
            "enumPropertyNaming" to "UPPERCASE",
            "serializationLibrary" to "kotlinx_serialization",
            "sourceFolder" to "src/commonMain/kotlin",
        ),
    )
}
