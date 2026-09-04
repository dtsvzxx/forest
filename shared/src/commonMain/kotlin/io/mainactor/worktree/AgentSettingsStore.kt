package io.mainactor.worktree

import io.mainactor.worktree.model.AgentSpec
import io.mainactor.worktree.model.BuiltInAgents
import io.mainactor.worktree.model.ProjectAgents
import io.mainactor.worktree.platform.FileSystemAccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Which agents each project offers, in `~/.worktree/agents.json`.
 *
 * JSON rather than the line-per-entry format the other two files use: this is a map of projects to
 * a set and a list of records, and inventing a separator layout for that is how a config file
 * becomes unparseable the first time someone's command line contains the separator.
 */
class AgentSettingsStore(private val fs: FileSystemAccess) {

    private val dir get() = fs.resolve(fs.homeDir(), ".worktree")
    private val file get() = fs.resolve(dir, "agents.json")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun load(): Map<String, ProjectAgents> {
        if (!fs.exists(file)) return emptyMap()
        return runCatching {
            json.parseToJsonElement(fs.readText(file)).jsonObject.mapValues { (_, value) ->
                value.jsonObject.toProjectAgents()
            }
        }.getOrDefault(emptyMap())
    }

    fun save(all: Map<String, ProjectAgents>) {
        runCatching {
            fs.createDirectories(dir)
            val root = buildJsonObject {
                all.forEach { (path, agents) ->
                    put(path, buildJsonObject {
                        put("enabled", buildJsonArray { agents.enabled.sorted().forEach { add(JsonPrimitive(it)) } })
                        put("custom", buildJsonArray {
                            agents.custom.forEach { spec ->
                                add(buildJsonObject {
                                    put("id", spec.id)
                                    put("name", spec.name)
                                    put("command", spec.command)
                                })
                            }
                        })
                    })
                }
            }
            fs.writeText(file, json.encodeToString(JsonObject.serializer(), root))
        }
    }

    /** What [projectPath] offers: its saved settings, or [defaults] when it has none. */
    fun forProjectOrDefault(projectPath: String): ProjectAgents = load()[projectPath] ?: defaults()

    /** Replaces one project's settings, leaving every other project's alone. */
    fun setForProject(projectPath: String, agents: ProjectAgents) {
        save(load() + (projectPath to agents))
    }

    /**
     * What a project that has never been configured offers.
     *
     * Every built-in whose executable can be found, plus the plain shell. Detection is a best
     * effort — see [FileSystemAccess.findOnPath] — and being wrong only costs a tick in the
     * settings dialog, so a false negative is cheap and a false positive is harmless.
     */
    fun defaults(): ProjectAgents = ProjectAgents(
        enabled = BuiltInAgents.all
            .filter { spec ->
                val executable = BuiltInAgents.executableOf(spec)
                executable == null || fs.findOnPath(executable) != null
            }
            .map { it.id }
            .toSet(),
    )

    private fun JsonObject.toProjectAgents(): ProjectAgents = ProjectAgents(
        enabled = this["enabled"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull() }?.toSet().orEmpty(),
        custom = this["custom"]?.jsonArray?.mapNotNull { element ->
            val obj = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            val id = obj["id"]?.jsonPrimitive?.contentOrNull() ?: return@mapNotNull null
            AgentSpec(
                id = id,
                name = obj["name"]?.jsonPrimitive?.contentOrNull() ?: id,
                command = obj["command"]?.jsonPrimitive?.contentOrNull().orEmpty(),
                builtIn = false,
            )
        }.orEmpty(),
    )

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? =
        runCatching { content }.getOrNull()?.takeIf { it.isNotBlank() }
}
