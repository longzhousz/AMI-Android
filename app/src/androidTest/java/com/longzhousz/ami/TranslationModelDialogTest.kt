package com.longzhousz.ami

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.longzhousz.ami.core.TranslationModel
import com.longzhousz.ami.core.TranslationModels
import com.longzhousz.ami.data.TranslationModelState
import com.longzhousz.ami.ui.AmiTheme
import com.longzhousz.ami.ui.TranslationModelDialog
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TranslationModelDialogTest {
    @get:Rule val compose = createComposeRule()
    @Test fun refreshingOfficialChoicesPreservesTheManualDraftUntilSaved() {
        val state = mutableStateOf(TranslationModelState(TranslationModels.defaults, TranslationModels.AUTO_FLASH, updatedAt = 1_792_857_600_000L))
        var selection: String? = null
        var refreshes = 0
        var updates: Boolean? = null
        compose.setContent {
            AmiTheme("dark") { TranslationModelDialog(state.value, true, { refreshes++ }, { updates = it }, { selection = it }, {}) }
        }
        compose.onNodeWithText("立即更新预设").performScrollTo().performClick()
        assertEquals(1, refreshes)
        compose.onNodeWithText("手动选择").performScrollTo().performClick()
        compose.onNodeWithText("手动输入模型 ID").performScrollTo().performClick()
        compose.onNodeWithTag("custom-model-id").performScrollTo().performTextInput("future-translation-model")
        compose.runOnIdle { state.value = state.value.copy(models = listOf(TranslationModel("deepseek-flash", "DeepSeek-V5-Flash"))) }
        compose.onNodeWithTag("custom-model-id").assertTextContains("future-translation-model")
        compose.onNodeWithText("保存").performClick()
        assertEquals("future-translation-model", selection)
        assertEquals(true, updates)
    }
    @Test fun missingKeyAndFailedRefreshKeepOfflineChoicesAvailableAndCancelHasNoEffects() {
        var saved = false
        compose.setContent {
            AmiTheme("dark") { TranslationModelDialog(TranslationModelState(TranslationModels.defaults, TranslationModels.AUTO_FLASH,
                error = "更新失败：网络暂不可用。已保留现有预设。"), false, {}, { saved = true }, { saved = true }, {}) }
        }
        compose.onNodeWithText("立即更新预设").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("更新失败：网络暂不可用。已保留现有预设。").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("自动跟随最新 Flash").performScrollTo().performClick()
        compose.onNodeWithText("保存").assertIsEnabled()
        compose.onNodeWithTag("automatic-model-updates").performScrollTo().performClick()
        compose.onNodeWithText("取消").performClick()
        assertFalse(saved)
    }
    @Test fun officialLatestModelNameAndRefreshStatusAreReadable() {
        val state = TranslationModelState(listOf(TranslationModel("deepseek-flash", "DeepSeek-V4.1-Flash"),
            TranslationModel("deepseek-v4-pro", "DeepSeek-V4-Pro")), TranslationModels.AUTO_FLASH, updatedAt = 1_792_857_600_000L)
        compose.setContent { AmiTheme("dark") { TranslationModelDialog(state, true, {}, {}, {}, {}) } }
        compose.onNodeWithText("自动跟随最新 Flash").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("DeepSeek-V4.1-Flash").onFirst().assertIsDisplayed()
        val bitmap = compose.onNodeWithTag("translation-model-dialog").captureToImage().asAndroidBitmap()
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "qa").apply { mkdirs() }
        File(root, "translation-models.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
