package io.github.hatake716.dango.ui.browser.components

import androidx.compose.ui.graphics.Color

/** タグ7色（SPEC §9 のトークン） */
val TAG_COLOR_VALUES: Map<String, Color> = mapOf(
    "red" to Color(0xFFFF5257),
    "orange" to Color(0xFFFFA234),
    "yellow" to Color(0xFFFFD848),
    "green" to Color(0xFF62C554),
    "blue" to Color(0xFF2E8EFF),
    "purple" to Color(0xFFC67BFF),
    "gray" to Color(0xFFA0A0A5),
)

fun tagLabelRes(tag: String): Int = when (tag) {
    "red" -> io.github.hatake716.dango.R.string.tag_red
    "orange" -> io.github.hatake716.dango.R.string.tag_orange
    "yellow" -> io.github.hatake716.dango.R.string.tag_yellow
    "green" -> io.github.hatake716.dango.R.string.tag_green
    "blue" -> io.github.hatake716.dango.R.string.tag_blue
    "purple" -> io.github.hatake716.dango.R.string.tag_purple
    else -> io.github.hatake716.dango.R.string.tag_gray
}
