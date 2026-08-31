package io.roadbook.karoo.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class WaterSubtypeTest {

    @Test
    fun `each subtype maps to its distinct label`() {
        assertEquals("Water tap", waterSubtypeLabel("tap"))
        assertEquals("Fountain", waterSubtypeLabel("fountain"))
        assertEquals("Spring", waterSubtypeLabel("spring"))
        assertEquals("Well", waterSubtypeLabel("well"))
        assertEquals("Graveyard", waterSubtypeLabel("graveyard"))
    }

    @Test
    fun `unknown subtype falls back to Water`() {
        assertEquals("Water", waterSubtypeLabel("something-new"))
        assertEquals("Water", waterSubtypeLabel(""))
    }
}
