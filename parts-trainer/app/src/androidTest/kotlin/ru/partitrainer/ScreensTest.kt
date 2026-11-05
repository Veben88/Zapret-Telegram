package ru.partitrainer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Инструментальные тесты основных экранов: главный, тренажёр, настройки, редактор. */
@RunWith(AndroidJUnit4::class)
class ScreensTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun str(id: Int) = rule.activity.getString(id)

    @Test fun homeShowsSourcesAndSample() {
        rule.onNodeWithText(str(R.string.src_pdf)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.src_sample)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.src_manual)).assertIsDisplayed()
    }

    @Test fun sampleOpensTrainerWithControls() {
        rule.onNodeWithText(str(R.string.src_sample)).performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText(str(R.string.play_all)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText(str(R.string.play_all)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.transpose)).assertIsDisplayed()
    }

    @Test fun settingsScreenOpensFromHome() {
        rule.onNodeWithContentDescription(str(R.string.settings)).performClick()
        rule.onNodeWithText(str(R.string.set_recognition)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.api_key)).assertIsDisplayed()
    }

    @Test fun manualScoreOpensEditor() {
        rule.onNodeWithText(str(R.string.src_manual)).performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText(str(R.string.all_correct)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText(str(R.string.all_correct)).assertIsDisplayed()
    }
}
