@file:OptIn(ExperimentalTextApi::class)

package com.guilnunes.herald.ui

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.guilnunes.herald.R

/** Scriptorium family faces, bundled as variable fonts (OFL, see third_party/fonts). */
object ScriptoriumFonts {
    private fun variable(res: Int, vararg weights: Int) = FontFamily(
        weights.map { w ->
            Font(res, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
        },
    )

    /** Atkinson Hyperlegible Next: all interface text. */
    val ui = variable(R.font.atkinson_hyperlegible_next, 400, 500, 600, 700)

    /** Literata: long-form reading. */
    val reading = variable(R.font.literata, 400, 600)

    /** Grenze Gotisch: display only, never below the title size. */
    val display = variable(R.font.grenze_gotisch, 500, 600)

    /** Atkinson Hyperlegible Mono: call numbers, identifiers, codes. */
    val mono = variable(R.font.atkinson_hyperlegible_mono, 400, 500)
}
