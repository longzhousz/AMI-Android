package com.longzhousz.ami.core

import org.junit.Assert.*
import org.junit.Test

class TranslationModelsTest {
    @Test fun officialDiscoveryMetadataIsUsedForThePicker() {
        val models = TranslationModels.parse("""{"data":[{"id":"deepseek-flash","name":"DeepSeek-V4.1-Flash","input_modalities":["text","image"],"output_modalities":["text"]},{"id":"deepseek-v4-pro","name":"DeepSeek-V4-Pro"}]}""")
        assertEquals("DeepSeek-V4.1-Flash", models.first().name)
        assertEquals("deepseek-flash", TranslationModels.resolve(TranslationModels.AUTO_FLASH, models).id)
        assertEquals(models, TranslationModels.parse(TranslationModels.encode(models)))
    }
    @Test fun canonicalMovingAliasWinsOverVersionLabelsAndListOrder() {
        val models = listOf(TranslationModel("deepseek-v100-flash"), TranslationModel("deepseek-flash", "Latest official Flash"))
        assertEquals("deepseek-flash", TranslationModels.resolve(TranslationModels.AUTO_FLASH, models).id)
        assertEquals("deepseek-flash", TranslationModels.resolve(TranslationModels.AUTO_FLASH, models.reversed()).id)
    }
    @Test fun automaticSelectionComparesVersionNumbersAndSkipsExperimentalModels() {
        val models = listOf("deepseek-v9-pro", "deepseek-v10-pro", "deepseek-v11-pro-preview", "deepseek-v11-pro-beta1", "deepseek-v11-pro-exp", "deepseek-v10.2-pro").map(::TranslationModel)
        assertEquals("deepseek-v10.2-pro", TranslationModels.resolve(TranslationModels.AUTO_PRO, models).id)
        assertEquals("deepseek-v10.2-pro", TranslationModels.resolve(TranslationModels.AUTO_PRO, models.reversed()).id)
    }
    @Test fun automaticFlashNeverSilentlySwitchesToPro() {
        assertThrows(IllegalStateException::class.java) {
            TranslationModels.resolve(TranslationModels.AUTO_FLASH, listOf(TranslationModel("deepseek-v10-pro")))
        }
    }
    @Test fun manualUnknownAndPreviewModelsRemainExplicitChoices() {
        assertEquals("future-translation-model", TranslationModels.resolve("future-translation-model", emptyList()).id)
        assertEquals("deepseek-v11-pro-preview", TranslationModels.resolve("deepseek-v11-pro-preview", emptyList()).id)
    }
    @Test fun nonTextAndInvalidEntriesAreExcludedAndDuplicatesRemoved() {
        val models = TranslationModels.parse("""{"data":[{"id":"deepseek-flash"},{"id":"deepseek-flash"},{"id":"image-model","input_modalities":["image"]},{"id":"audio-model","output_modalities":["audio"]},{"id":"deepseek-embedding"},{"id":"bad id"},null,{"id":"future-model","name":"Future Text Model","input_modalities":["text"]}]}""")
        assertEquals(listOf("deepseek-flash", "future-model"), models.map { it.id })
    }
    @Test fun emptyMalformedAndIncompatibleCatalogsFailInsteadOfErasingPresets() {
        assertThrows(Exception::class.java) { TranslationModels.parse("not JSON") }
        assertThrows(Exception::class.java) { TranslationModels.parse("""{"data":[]}""") }
        assertThrows(Exception::class.java) { TranslationModels.parse("""{"data":[{"id":"no-text","output_modalities":["audio"]}]}""") }
    }
    @Test fun legacyDefaultsMigrateButManualProAndUnknownSelectionsArePreserved() {
        assertEquals(TranslationModels.AUTO_FLASH, TranslationModels.migrate(null))
        assertEquals(TranslationModels.AUTO_FLASH, TranslationModels.migrate("deepseek-v4-flash"))
        assertEquals(TranslationModels.AUTO_FLASH, TranslationModels.migrate("deepseek-reasoner"))
        assertEquals("deepseek-v4-pro", TranslationModels.migrate("deepseek-v4-pro"))
        assertEquals("my-future-model", TranslationModels.migrate("my-future-model"))
    }
    @Test fun manualIdsRejectWhitespaceAndInvalidSelectionSentinels() {
        assertFalse(TranslationModels.validId(""))
        assertFalse(TranslationModels.validId("deepseek\nflash"))
        assertFalse(TranslationModels.validId("x".repeat(129)))
        assertFalse(TranslationModels.validSelection("auto:unknown"))
        assertTrue(TranslationModels.validSelection(TranslationModels.AUTO_FLASH))
    }
}
