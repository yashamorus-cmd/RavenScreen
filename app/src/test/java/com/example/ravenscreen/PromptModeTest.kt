package com.example.ravenscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptModeTest {

    @Test
    fun everyModeEndsWithFormatRules() {
        for (mode in PromptMode.entries) {
            val prompt = PromptMode.buildPrompt(mode, "Що тут написано?")
            assertTrue("$mode без правил форматування", prompt.endsWith(PromptMode.FORMAT_RULES))
        }
    }

    @Test
    fun builtInModesUseTheirOwnInstruction() {
        for (mode in listOf(PromptMode.SHORT, PromptMode.DETAILED, PromptMode.TRANSLATE)) {
            assertTrue(PromptMode.buildPrompt(mode, null).startsWith(mode.instruction))
        }
    }

    @Test
    fun customModeSendsUserTextTrimmed() {
        val prompt = PromptMode.buildPrompt(PromptMode.CUSTOM, "  Знайди помилку в коді  ")
        assertTrue(prompt.startsWith("Це скріншот мого екрана. Знайди помилку в коді "))
    }

    @Test
    fun blankCustomTextFallsBackToShort() {
        assertEquals(PromptMode.SHORT, PromptMode.effective(PromptMode.CUSTOM, "   "))
        assertEquals(
            PromptMode.buildPrompt(PromptMode.SHORT, null),
            PromptMode.buildPrompt(PromptMode.CUSTOM, null),
        )
    }

    @Test
    fun customTextDoesNotAffectOtherModes() {
        assertEquals(PromptMode.DETAILED, PromptMode.effective(PromptMode.DETAILED, ""))
        assertEquals(
            PromptMode.buildPrompt(PromptMode.TRANSLATE, null),
            PromptMode.buildPrompt(PromptMode.TRANSLATE, "щось своє"),
        )
    }

    @Test
    fun fromPrefRestoresSavedModeAndDefaultsToShort() {
        for (mode in PromptMode.entries) assertEquals(mode, PromptMode.fromPref(mode.prefValue))
        assertEquals(PromptMode.SHORT, PromptMode.fromPref(null))
        assertEquals(PromptMode.SHORT, PromptMode.fromPref("щось невідоме"))
    }
}
