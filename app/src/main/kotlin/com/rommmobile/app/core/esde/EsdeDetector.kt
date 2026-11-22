package com.rommmobile.app.core.esde

import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import com.rommmobile.app.core.storage.FileGateway
import com.rommmobile.app.core.storage.FileGatewayFactory
import com.rommmobile.app.core.storage.StorageRoot
import com.rommmobile.app.core.storage.normalizeFolderName
import com.rommmobile.app.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.BufferedReader
import com.rommmobile.app.core.storage.StorageLocations
import java.io.File
import java.io.StringReader
import javax.inject.Inject
import javax.xml.parsers.DocumentBuilderFactory

data class EsdeDetection(
    val folder: String? = null,
    val retroArchConfigRead: Boolean? = null,
    val launches: List<EsdeLaunch> = emptyList(),
    val error: Boolean = false,
)

data class EsdeLaunch(
    val system: String,
    val game: String?,
    val label: String,
    val packageName: String?,
    val core: String?,
    val saves: String?,
    val states: String?,
    val romDirectory: String? = null,
    val saveFoldersVerified: Boolean = false,
    val folderSelectionKey: String? = null,
    val savesAutoDetected: Boolean = false,
    val statesAutoDetected: Boolean = false,
)

class EsdeDetector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val log: FileLogger,
) {
    private val shared = Environment.getExternalStorageDirectory()
    private val corePattern = Regex("""([^/\s]+)_libretro(?:_android)?\.so""")
    private val emulatorPattern = Regex("""%EMULATOR_([^%]+)%""")
    private val packagePattern = Regex("""%ANDROIDAPP%=([^\s]+)""")
    private val configPattern = Regex("""%EXTRA_CONFIGFILE%=([^\s]+)""")

    fun detect(romRoot: StorageRoot?, dataTree: String?): EsdeDetection = try {
        inspect(romRoot, dataTree)
    } catch (e: Exception) {
        log.w("EsdeDetector", "Could not read ES-DE configuration", e)
        EsdeDetection(error = true)
    }

    private fun inspect(romRoot: StorageRoot?, dataTree: String?): EsdeDetection {
        val data = findData(romRoot, dataTree) ?: return EsdeDetection()
        val settings = data.xml("settings", "es_settings.xml", fragment = true)
            ?: return EsdeDetection(error = true)
        fun enabled(key: String, fallback: Boolean) = settings.children("bool")
            .firstOrNull { it.getAttribute("name") == key }
            ?.getAttribute("value")?.toBooleanStrictOrNull() ?: fallback
        val systemsFile = if (enabled("RetroArchSAFMode", false)) "es_systems_saf.xml" else "es_systems.xml"
        val custom = data.xml("custom_systems", "es_systems.xml")
        val systems = custom?.children("system").orEmpty().associateBy { it.text("name") }.toMutableMap()
        if (custom?.children("loadExclusive").isNullOrEmpty()) {
            for (definition in asset(systemsFile).children("system")) {
                systems.putIfAbsent(definition.text("name"), definition)
            }
        }
        val rules = (asset("es_find_rules.xml").children("emulator") +
            data.xml("custom_systems", "es_find_rules.xml")?.children("emulator").orEmpty())
            .associateBy { it.getAttribute("name") }
        val roms = romRoot?.let { FileGatewayFactory.create(context, it) }
        val overridesEnabled = enabled("AlternativeEmulatorPerGame", true)
        val profiles = mutableMapOf<String, Map<String, String>?>()
        val launches = mutableListOf<EsdeLaunch>()
        for ((system, definition) in systems.toSortedMap()) {
            if (system.isEmpty() || '/' in system || system == "." || system == "..") continue
            val document = data.xml("gamelists/$system", "gamelist.xml", fragment = true)
            val gamelist = document?.children("gameList")?.firstOrNull()
            val games = gamelist?.children("game").orEmpty()
            if (games.isEmpty() && !hasContent(roms, definition)) continue
            val commands = definition.children("command")
            val default = commands.firstOrNull() ?: continue
            // ES-DE versions can store the system choice inside or alongside gameList.
            val systemChoice = (gamelist?.children("alternativeEmulator").orEmpty() +
                document?.children("alternativeEmulator").orEmpty()).firstOrNull()?.text("label").orEmpty()
            fun choice(label: String) = commands.firstOrNull { it.getAttribute("label") == label } ?: default
            val location = definition.text("path")
            val romDirectory = when {
                location.startsWith("%ROMPATH%/") && roms != null ->
                    roms.displayPath(location.removePrefix("%ROMPATH%/"))
                location.startsWith('/') -> location
                else -> null
            }
            launches += resolve(system, null, choice(systemChoice), rules, profiles, data).copy(romDirectory = romDirectory)
            if (!overridesEnabled) continue
            for (game in games) {
                val label = game.text("altemulator")
                if (label.isNotEmpty()) {
                    launches += resolve(system, game.text("path"), choice(label), rules, profiles, data)
                        .copy(romDirectory = romDirectory)
                }
            }
        }
        return EsdeDetection(data.root.displayName, profiles.values.takeIf { it.isNotEmpty() }?.all { it != null }, launches)
    }

    private fun hasContent(roms: FileGateway?, system: Element): Boolean {
        val location = system.text("path")
        val path = location.removePrefix("%ROMPATH%/")
        val extensions = system.text("extension").split(Regex("\\s+")).filter { it.startsWith(".") }
        fun containsRom(files: List<com.rommmobile.app.core.storage.FileInfo>) = files.any { entry ->
            extensions.any { entry.name.endsWith(it, ignoreCase = true) }
        }
        if (location.startsWith("/")) {
            return File(path).listFiles()?.any { file -> extensions.any { file.name.endsWith(it, true) } } == true
        }
        return location.startsWith("%ROMPATH%/") && roms != null && containsRom(roms.list(path))
    }

    private fun resolve(
        system: String,
        game: String?,
        command: Element,
        rules: Map<String, Element>,
        profiles: MutableMap<String, Map<String, String>?>,
        data: FileGateway,
    ): EsdeLaunch {
        val text = command.textContent.trim()
        val label = command.getAttribute("label")
        val emulator = emulatorPattern.find(text)?.groupValues?.get(1)
        val candidates = rules[emulator]?.children("rule").orEmpty()
            .filter { it.getAttribute("type") == "androidpackage" }
            .flatMap { it.children("entry") }
            .map { it.textContent.trim().substringBefore('/') }
        val explicitPackage = packagePattern.find(text)?.groupValues?.get(1)
            ?.substringBefore('/')?.takeUnless { '%' in it }
        val packageName = explicitPackage ?: candidates.firstOrNull(::installed)
        val core = corePattern.find(text)?.groupValues?.get(1)
        if (packageName == null || !packageName.startsWith("com.retroarch") || core == null) {
            return EsdeLaunch(system, game, label, packageName, core, null, null)
        }

        val configFile = configPattern.find(text)?.groupValues?.get(1)
            ?.replace("%EXTERNALDATA%", shared.path)?.replace("%ANDROIDPACKAGE%", packageName)
            ?.takeIf { it.startsWith('/') }
        val cfg = configFile?.let {
            if (it !in profiles) {
                val live = runCatching { File(it).bufferedReader().use(::readRetroArchConfig) }.getOrNull()
                val defaultPath = File(shared, "Android/data/$packageName/files/retroarch.cfg").path
                profiles[it] = live ?: if (it == defaultPath) {
                    // Android can block another app's live config. Accept an explicit exported copy.
                    data.openInput("romm-save-sync", "$packageName.cfg")?.bufferedReader()
                        ?.use(::readRetroArchConfig)
                } else null
            }
            profiles[it]
        }
        val key = listOf(packageName, configFile.orEmpty(), core).joinToString("\n")
        val chosenSaves = selectedFolder(key, false)
        val chosenStates = selectedFolder(key, true)
        val saves = chosenSaves?.let(::File)?.let(::usableFolder)
            ?: if (chosenSaves == null) folder(cfg, "savefile", "saves", core, label) else null
        val states = chosenStates?.let(::File)?.let(::usableFolder)
            ?: if (chosenStates == null) folder(cfg, "savestate", "states", core, label) else null
        return EsdeLaunch(system, game, label, packageName, core, saves, states,
            saveFoldersVerified = saves != null || states != null, folderSelectionKey = key,
            savesAutoDetected = chosenSaves == null && cfg == null && saves != null,
            statesAutoDetected = chosenStates == null && cfg == null && states != null)
    }

    private val selectedFolders = context.getSharedPreferences("selected-emulator-folders", Context.MODE_PRIVATE)

    fun selectSharedFolder(key: String, state: Boolean, path: String): Boolean {
        val directory = runCatching { File(path).canonicalFile }.getOrNull() ?: return false
        if (usableFolder(directory) == null) return false
        selectedFolders.edit().putString(folderKey(key, state), directory.path).apply()
        return true
    }

    private fun selectedFolder(key: String, state: Boolean): String? =
        selectedFolders.getString(folderKey(key, state), null)

    private fun folderKey(key: String, state: Boolean): String = "$key\n$state"

    private fun folder(cfg: Map<String, String>?, key: String, kind: String, core: String, label: String): String? {
        if (cfg?.get("${key}s_in_content_dir") == "true" ||
            cfg?.get("sort_${key}s_by_content_enable") == "true") return null
        val configured = cfg?.get("${key}_directory")?.takeIf { it.startsWith('/') }
        val root = configured?.let(::File) ?: File(shared, "RetroArch/$kind")
        if (configured != null && cfg?.get("sort_${key}s_enable") != "true") {
            return usableFolder(root)
        }
        // RetroArch uses the core's library name, which can differ from its binary or ES-DE label.
        val names = setOf(normalizeFolderName(core), normalizeFolderName(label))
        val grant = StorageLocations.treeCovering(context, root.absolutePath, writable = true)
        val folders = if (StorageLocations.hasAllFilesAccess(context)) {
            root.listFiles().orEmpty().filter(File::isDirectory).map(File::getName)
        } else if (grant != null) {
            FileGatewayFactory.create(context, grant.first).listDirs(grant.second)
        } else emptyList()
        return folders.singleOrNull { normalizeFolderName(it) in names }
            ?.let { usableFolder(File(root, it)) }
    }

    private fun usableFolder(file: File): String? =
        file.absolutePath.takeIf {
            StorageLocations.treeCovering(context, it, writable = true) != null ||
                StorageLocations.hasAllFilesAccess(context) && file.list() != null && file.canWrite()
        }

    private fun readRetroArchConfig(reader: BufferedReader): Map<String, String> =
        reader.lineSequence().mapNotNull { line ->
            val key = line.substringBefore('=').trim()
            if ('=' !in line || key.startsWith('#')) null
            else key to line.substringAfter('=').trim().removeSurrounding("\"")
        }.toMap()

    private fun findData(romRoot: StorageRoot?, dataTree: String?): FileGateway? {
        if (dataTree != null) {
            return FileGatewayFactory.create(context, StorageRoot.Saf(dataTree))
                .takeIf { it.exists("settings", "es_settings.xml") }
        }
        val volumes = listOfNotNull(romRoot?.displayName?.let { File(it).parentFile }, shared).distinct()
        val candidates = volumes.flatMap { parent ->
            listOf(File(parent, "ES-DE")) + parent.listFiles().orEmpty().filter(File::isDirectory)
        }.distinct().filter { File(it, "settings/es_settings.xml").canRead() }
        return candidates.singleOrNull()?.let {
            FileGatewayFactory.create(context, StorageRoot.Direct(it.path))
        }
    }

    private fun installed(name: String): Boolean = try {
        context.packageManager.getApplicationInfo(name, 0).enabled
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun asset(name: String): Element =
        context.assets.open("es-de/$name").bufferedReader().use { parse(it.readText()) }

    private fun FileGateway.xml(dir: String, name: String, fragment: Boolean = false): Element? =
        openInput(dir, name)?.bufferedReader()?.use {
            val xml = it.readText().trimStart('\uFEFF').trimStart()
            if (fragment) {
                val content = xml.replaceFirst(Regex("""^<\?xml[^?]*\?>"""), "")
                parse("<esde>$content</esde>")
            } else {
                parse(xml)
            }
        }

    private fun parse(xml: String): Element {
        require("<!DOCTYPE" !in xml) { "DOCTYPE is not supported" }
        return DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(InputSource(StringReader(xml))).documentElement
    }

    private fun Element.children(tag: String): List<Element> =
        (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }.filter { it.tagName == tag }

    private fun Element.text(tag: String): String = children(tag).firstOrNull()?.textContent?.trim().orEmpty()
}
