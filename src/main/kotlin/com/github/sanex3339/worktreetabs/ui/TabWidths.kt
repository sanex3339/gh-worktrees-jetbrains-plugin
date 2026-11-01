package com.github.sanex3339.worktreetabs.ui

/** How tabs flow into rows when they wrap. */
internal object TabWidths {
    /** Groups tab indices into rows of [available] pixels, in order, each row holding as many tabs as fit. */
    fun rows(preferred: List<Int>, available: Int, gap: Int): List<List<Int>> {
        val rows = mutableListOf<MutableList<Int>>()
        var used = 0
        for ((index, width) in preferred.withIndex()) {
            val row = rows.lastOrNull()
            if (row != null && used + gap + width <= available) {
                row += index
                used += gap + width
            } else {
                rows += mutableListOf(index)
                used = width
            }
        }
        return rows
    }
}
