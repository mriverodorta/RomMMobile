package com.rommmobile.app.core.esde

/** Standalone launch profiles use their own storage and never share a RetroArch slot. */
internal enum class StandaloneEmulator(val id: String) {
    DUCKSTATION("duckstation"),
    DOLPHIN_GC("dolphin-gc"),
    DOLPHIN_WII("dolphin-wii"),
    LIME3DS("lime3ds"),
    PPSSPP("ppsspp");

    fun normalizeGameId(value: String): String? {
        val id = value.trim().uppercase()
        return when (this) {
            DUCKSTATION -> DUCK_ID.matchEntire(id)?.let { "${it.groupValues[1]}-${it.groupValues[2]}" }
            DOLPHIN_GC, DOLPHIN_WII -> id.takeIf { DOLPHIN_ID.matches(it) }
            LIME3DS -> id.takeIf { CTR_ID.matches(it) }
            PPSSPP -> id.takeIf { PSP_ID.matches(it) }
        }
    }

    fun defaultPaths(packageName: String, state: Boolean): List<String> {
        val external = "Android/data/$packageName/files"
        return when (this) {
            DUCKSTATION -> {
                val dir = if (state) "savestates" else "memcards"
                listOf("$external/$dir", "duckstation/$dir")
            }
            DOLPHIN_GC, DOLPHIN_WII -> {
                val dir = when {
                    state -> "StateSaves"
                    this == DOLPHIN_GC -> "GC"
                    else -> "Wii/title/00010000"
                }
                listOf("$external/$dir", "dolphin-emu/$dir")
            }
            LIME3DS -> if (state) listOf("3DS/states", "$external/states") else emptyList()
            PPSSPP -> {
                val dir = if (state) "PPSSPP_STATE" else "SAVEDATA"
                listOf("PSP/$dir", "$external/PSP/$dir")
            }
        }
    }

    companion object {
        private val DUCK_ID = Regex("([A-Z]{4})[-_]?(\\d{5})")
        private val DOLPHIN_ID = Regex("[A-Z0-9]{6}")
        private val CTR_ID = Regex("00040000[A-F0-9]{8}")
        private val PSP_ID = Regex("[A-Z]{4}[0-9]{5}")

        fun forPlatform(slug: String): StandaloneEmulator? = when (slug) {
            "psx", "ps1", "playstation" -> DUCKSTATION
            "gc", "ngc", "gamecube" -> DOLPHIN_GC
            "wii" -> DOLPHIN_WII
            "n3ds", "3ds" -> LIME3DS
            "psp" -> PPSSPP
            else -> null
        }

        fun detect(packageName: String?, system: String): StandaloneEmulator? = when {
            packageName == "com.github.stenzek.duckstation" && system == "psx" -> DUCKSTATION
            packageName == "org.dolphinemu.dolphinemu" && system == "gc" -> DOLPHIN_GC
            packageName == "org.dolphinemu.dolphinemu" && system == "wii" -> DOLPHIN_WII
            packageName == "io.github.lime3ds.android" && system == "n3ds" -> LIME3DS
            packageName in setOf("org.ppsspp.ppsspp", "org.ppsspp.ppssppgold") && system == "psp" -> PPSSPP
            else -> null
        }
    }
}
