package com.github.sanex3339.worktreetabs.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TabWidthsTest {
    @Test
    fun `rows take as many tabs as fit at their own widths`() {
        assertEquals(listOf(listOf(0, 1), listOf(2, 3), listOf(4)), TabWidths.rows(listOf(200, 200, 200, 150, 400), available = 410, gap = 4))
        assertEquals(listOf(listOf(0)), TabWidths.rows(listOf(900), available = 410, gap = 4))
    }
}
