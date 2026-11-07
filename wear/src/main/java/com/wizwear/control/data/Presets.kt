package com.wizwear.control.data

data class ColorPreset(val name: String, val r: Int, val g: Int, val b: Int)
data class TempPreset(val name: String, val kelvin: Int)
data class Scene(val id: Int, val name: String)

object Presets {
    val temps = listOf(
        TempPreset("Warm", 2700),
        TempPreset("Neutral", 4000),
        TempPreset("Daylight", 5000),
        TempPreset("Cool", 6500),
    )

    val colors = listOf(
        ColorPreset("Red", 255, 0, 0),
        ColorPreset("Orange", 255, 110, 0),
        ColorPreset("Yellow", 255, 200, 0),
        ColorPreset("Green", 0, 255, 40),
        ColorPreset("Teal", 0, 220, 200),
        ColorPreset("Blue", 0, 60, 255),
        ColorPreset("Purple", 140, 0, 255),
        ColorPreset("Pink", 255, 0, 140),
    )

    /** Built-in WiZ scene IDs (same IDs the WiZ app uses). */
    val scenes = listOf(
        Scene(11, "Warm white"),
        Scene(12, "Daylight"),
        Scene(13, "Cool white"),
        Scene(14, "Night light"),
        Scene(15, "Focus"),
        Scene(16, "Relax"),
        Scene(18, "TV time"),
        Scene(6, "Cozy"),
        Scene(10, "Bedtime"),
        Scene(9, "Wake up"),
        Scene(29, "Candlelight"),
        Scene(30, "Golden white"),
        Scene(5, "Fireplace"),
        Scene(3, "Sunset"),
        Scene(1, "Ocean"),
        Scene(7, "Forest"),
        Scene(2, "Romance"),
        Scene(4, "Party"),
        Scene(26, "Club"),
        Scene(8, "Pastel colors"),
        Scene(17, "True colors"),
        Scene(20, "Spring"),
        Scene(21, "Summer"),
        Scene(22, "Fall"),
        Scene(23, "Deep dive"),
        Scene(24, "Jungle"),
        Scene(25, "Mojito"),
        Scene(27, "Christmas"),
        Scene(28, "Halloween"),
        Scene(31, "Pulse"),
        Scene(32, "Steampunk"),
        Scene(19, "Plant growth"),
    )

    fun sceneName(id: Int?): String? = scenes.firstOrNull { it.id == id }?.name

    /** White-only scenes a tunable-white bulb can play (same set the WiZ app offers). */
    private val tunableWhiteScenes = setOf(6, 9, 10, 11, 12, 13, 14, 15, 16, 18, 29, 30, 31, 32)
    /** Brightness-only scenes for dimmable bulbs. */
    private val dimmableScenes = setOf(9, 10, 13, 14, 29, 30, 31, 32)

    fun scenesFor(kind: com.wizwear.control.net.BulbKind?): List<Scene> = when (kind) {
        com.wizwear.control.net.BulbKind.TUNABLE_WHITE -> scenes.filter { it.id in tunableWhiteScenes }
        com.wizwear.control.net.BulbKind.DIMMABLE -> scenes.filter { it.id in dimmableScenes }
        else -> scenes
    }

    /** Name of the colour preset the light is currently showing, if any. */
    fun colorName(s: com.wizwear.control.net.PilotState?): String? =
        if (s == null || !s.on) null else colors.firstOrNull { it.r == s.r && it.g == s.g && it.b == s.b }?.name
}
