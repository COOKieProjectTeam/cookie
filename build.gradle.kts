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
    alias(libs.plugins.openapi.generator)
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

abstract class ValidateServiceDescriptorsTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val descriptors: ConfigurableFileCollection = project.objects.fileCollection()

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val publicContracts: ConfigurableFileCollection = project.objects.fileCollection()

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val serviceModel: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val eventModel: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val runtimeContract: RegularFileProperty

    @TaskAction
    fun validateDescriptors() {
        val descriptorFiles = descriptors.files.sortedBy { it.path }
        require(descriptorFiles.isNotEmpty()) { "At least one service descriptor is required" }

        val yaml = Yaml(SafeConstructor(LoaderOptions()))
        val architecture = stringMap(
            yaml.load<Any>(serviceModel.get().asFile.readText()),
            serviceModel.get().asFile.path,
        )
        val defaults = stringMap(architecture["defaults"], "architecture defaults")
        val services = linkedMapOf<String, LinkedHashMap<String, Any?>>()
        listValue(architecture["services"], "architecture services").forEach { serviceValue ->
            val service = stringMap(serviceValue, "architecture service")
            val id = requiredString(service["id"], "architecture service id")
            require(services.putIfAbsent(id, service) == null) { "Duplicate architecture service id $id" }
        }

        val eventsDocument = stringMap(
            yaml.load<Any>(eventModel.get().asFile.readText()),
            eventModel.get().asFile.path,
        )
        val events = linkedMapOf<Pair<String, Int>, LinkedHashMap<String, Any?>>()
        listValue(eventsDocument["events"], "architecture events").forEach { eventValue ->
            val event = stringMap(eventValue, "architecture event")
            val type = requiredString(event["type"], "architecture event type")
            val version = positiveInt(event["version"], "architecture event $type version")
            require(events.putIfAbsent(type to version, event) == null) {
                "Duplicate architecture event $type v$version"
            }
        }

        val contracts = publicContracts.files.associateBy { it.nameWithoutExtension }
        require(contracts.size == publicContracts.files.size) { "Duplicate public contract service id" }
        val descriptorIdsWithPublicContracts = linkedSetOf<String>()
        val descriptorIds = linkedSetOf<String>()

        descriptorFiles.forEach { descriptorFile ->
            val context = descriptorFile.path
            val descriptor = stringMap(yaml.load<Any>(descriptorFile.readText()), context)
            require(descriptor.keys.all(ALLOWED_DESCRIPTOR_FIELDS::contains)) {
                "$context contains unsupported fields ${descriptor.keys - ALLOWED_DESCRIPTOR_FIELDS}"
            }
            require(positiveInt(descriptor["schema_version"], "$context schema_version") == 1) {
                "$context uses an unsupported schema_version"
            }

            val id = requiredString(descriptor["id"], "$context id")
            require(descriptorIds.add(id)) { "Duplicate service descriptor id $id" }
            require(descriptorFile.parentFile.name == id) {
                "$context must live under backend/services/$id"
            }
            val service = services[id] ?: error("$context references unknown architecture service $id")

            val expectedRuntime = service["runtime"]?.toString()
                ?: requiredString(defaults["backend_runtime"], "architecture default backend_runtime")
            require(descriptor["runtime"]?.toString() == expectedRuntime) {
                "$context runtime ${descriptor["runtime"]} does not match architecture runtime $expectedRuntime"
            }
            require(descriptor["stateful"] == service["stateful"]) {
                "$context stateful=${descriptor["stateful"]} does not match architecture stateful=${service["stateful"]}"
            }
            if (descriptor["stateful"] == true) {
                requiredString(descriptor["database"], "$context database")
            }

            descriptor["public_openapi"]?.let { publicOpenApiValue ->
                val publicOpenApi = stringMap(publicOpenApiValue, "$context public_openapi")
                require(publicOpenApi.keys == setOf("source")) {
                    "$context public_openapi supports only the source field"
                }
                val expectedPublicSource = "contracts/openapi/public/$id.yaml"
                require(publicOpenApi["source"] == expectedPublicSource) {
                    "$context public_openapi.source must be $expectedPublicSource"
                }
                val contractFile = contracts[id] ?: error("$context has no active public OpenAPI contract")
                descriptorIdsWithPublicContracts += id
                validateContractOwnership(
                    yaml = yaml,
                    contractFile = contractFile,
                    serviceId = id,
                    ownedTags = stringSet(service["openapi_tags"], "architecture service $id openapi_tags"),
                )
            }

            require(descriptor["runtime_openapi"] == "contracts/openapi/runtime.yaml") {
                "$context runtime_openapi must be contracts/openapi/runtime.yaml"
            }

            val messaging = stringMap(descriptor["messaging"], "$context messaging")
            require(messaging.keys == setOf("publishes", "consumes")) {
                "$context messaging supports only publishes and consumes"
            }
            val published = descriptorEvents(messaging["publishes"], "$context messaging.publishes")
            val consumed = descriptorEvents(messaging["consumes"], "$context messaging.consumes")
            require(published.mapTo(linkedSetOf()) { it.first } == stringSet(service["publishes"], "service $id publishes")) {
                "$context publishes do not match docs/architecture/model/services.yaml"
            }
            require(consumed.mapTo(linkedSetOf()) { it.first } == stringSet(service["consumes"], "service $id consumes")) {
                "$context consumes do not match docs/architecture/model/services.yaml"
            }
            published.forEach { eventKey ->
                val event = events[eventKey] ?: error("$context publishes unknown event ${eventKey.first} v${eventKey.second}")
                require(event["producer"] == id) {
                    "$context publishes ${eventKey.first} but its architecture producer is ${event["producer"]}"
                }
            }
            consumed.forEach { eventKey ->
                val event = events[eventKey] ?: error("$context consumes unknown event ${eventKey.first} v${eventKey.second}")
                require(id in stringSet(event["consumers"], "event ${eventKey.first} consumers")) {
                    "$context consumes ${eventKey.first} but is not an architecture consumer"
                }
            }

            require(
                stringSet(descriptor["synchronous_dependencies"], "$context synchronous_dependencies") ==
                    stringSet(service["synchronous_dependencies"], "service $id synchronous_dependencies"),
            ) {
                "$context synchronous_dependencies do not match docs/architecture/model/services.yaml"
            }

            val probes = stringMap(descriptor["probes"], "$context probes")
            require(probes == linkedMapOf("liveness" to "/healthz", "readiness" to "/readyz")) {
                "$context probes must implement the shared runtime contract"
            }
        }

        require(contracts.keys == descriptorIdsWithPublicContracts) {
            "Active public contracts ${contracts.keys} and descriptors $descriptorIdsWithPublicContracts differ"
        }
        require(runtimeContract.get().asFile.isFile) { "Runtime OpenAPI contract is missing" }
    }

    private fun validateContractOwnership(
        yaml: Yaml,
        contractFile: java.io.File,
        serviceId: String,
        ownedTags: Set<String>,
    ) {
        val document = stringMap(yaml.load<Any>(contractFile.readText()), contractFile.path)
        val declaredTags = listValue(document["tags"], "${contractFile.path} tags").mapTo(linkedSetOf()) { tagValue ->
            requiredString(stringMap(tagValue, "${contractFile.path} tag")["name"], "${contractFile.path} tag.name")
        }
        require(declaredTags == ownedTags) {
            "${contractFile.path} declares tags $declaredTags but service $serviceId owns $ownedTags"
        }
        stringMap(document["paths"], "${contractFile.path} paths").forEach { (route, pathItemValue) ->
            stringMap(pathItemValue, "${contractFile.path} path $route")
                .filterKeys(HTTP_METHODS::contains)
                .forEach { (method, operationValue) ->
                    val operation = stringMap(operationValue, "${contractFile.path} $method $route")
                    val operationTags = stringSet(
                        operation["tags"],
                        "${contractFile.path} $method $route tags",
                    )
                    require(operationTags.isNotEmpty() && operationTags.all(ownedTags::contains)) {
                        "$method $route uses tags $operationTags but service $serviceId owns $ownedTags"
                    }
                }
        }
    }

    private fun descriptorEvents(value: Any?, context: String): Set<Pair<String, Int>> {
        val result = linkedSetOf<Pair<String, Int>>()
        listValue(value, context).forEach { eventValue ->
            val event = stringMap(eventValue, context)
            require(event.keys == setOf("type", "version")) { "$context entries require only type and version" }
            val type = requiredString(event["type"], "$context type")
            val version = positiveInt(event["version"], "$context $type version")
            require(result.add(type to version)) { "$context contains duplicate $type v$version" }
        }
        return result
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

    private fun stringSet(value: Any?, context: String): Set<String> =
        listValue(value, context).mapTo(linkedSetOf()) { requiredString(it, context) }

    private fun requiredString(value: Any?, context: String): String =
        (value as? String)?.takeIf(String::isNotBlank) ?: error("$context must be a non-blank string")

    private fun positiveInt(value: Any?, context: String): Int {
        val number = value as? Number ?: error("$context must be a positive integer")
        val integer = number.toInt()
        require(integer > 0 && number.toDouble() == integer.toDouble()) { "$context must be a positive integer" }
        return integer
    }

    companion object {
        private val ALLOWED_DESCRIPTOR_FIELDS = setOf(
            "schema_version",
            "id",
            "runtime",
            "stateful",
            "database",
            "public_openapi",
            "runtime_openapi",
            "messaging",
            "synchronous_dependencies",
            "infrastructure_dependencies",
            "probes",
            "access",
        )
        private val HTTP_METHODS = setOf("get", "put", "post", "delete", "options", "head", "patch", "trace")
    }
}

val bundledPublicSpec = layout.buildDirectory.file("generated/openapi/bundled/public.yaml")
val validatePlannedOpenApi = tasks.register<ValidateTask>("validatePlannedOpenApi") {
    group = "verification"
    description = "Validate the non-active public API roadmap without using it for generation."
    inputSpec.set(layout.projectDirectory.file("contracts/openapi/planned.yaml").asFile.absolutePath)
}

val validateServiceDescriptors = tasks.register<ValidateServiceDescriptorsTask>("validateServiceDescriptors") {
    group = "verification"
    description = "Validate service descriptors against architecture events, services and active OpenAPI contracts."
    descriptors.from(fileTree("backend/services") { include("*/service.yaml") })
    publicContracts.from(fileTree("contracts/openapi/public") { include("*.yaml") })
    serviceModel.set(layout.projectDirectory.file("docs/architecture/model/services.yaml"))
    eventModel.set(layout.projectDirectory.file("docs/architecture/model/events.yaml"))
    runtimeContract.set(layout.projectDirectory.file("contracts/openapi/runtime.yaml"))
}

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
            "sourceFolder" to "src/commonMain/kotlin",
        ),
    )
    typeMappings.set(
        mapOf(
            "object" to "JsonElement",
            "AnyType" to "JsonElement",
        ),
    )
    importMappings.set(mapOf("JsonElement" to "kotlinx.serialization.json.JsonElement"))
}

tasks.register("compileKmpPublicClient") {
    group = "verification"
    description = "Generate the active public KMP client and compile its JVM target."
    dependsOn(validateServiceDescriptors, ":apps:mobile:shared:compileKotlinJvm")
}
