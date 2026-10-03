package com.noisedetected.app.shots

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.noisedetected.app.AppFrame
import com.noisedetected.app.LiveState
import com.noisedetected.app.ui.CompareScreen
import com.noisedetected.app.ui.IdentifyScreen
import com.noisedetected.app.ui.RecordsScreen
import com.noisedetected.app.ui.SurveyScreen
import com.noisedetected.app.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test

/** 界面截图：./gradlew :app:recordPaparazziDebug，输出在 app/src/test/snapshots/images/。 */
class Shots {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6, maxPercentDifference = 0.1)

    private fun shot(tab: Int, content: @Composable (Modifier) -> Unit) = paparazzi.snapshot {
        AppTheme { AppFrame(tab, {}, content) }
    }

    private fun identify(state: LiveState, withData: Boolean = true) = shot(0) {
        IdentifyScreen(state, if (withData) Fixtures.renderer() else com.noisedetected.app.ui.DisplayRenderer(), {}, {}, {}, { _, _ -> }, {}, it)
    }

    @Test fun identifyRunning() = identify(Fixtures.liveRunning)
    @Test fun identifyIdle() = identify(Fixtures.liveIdle, withData = false)
    @Test fun identifyAnalyzing() = identify(Fixtures.liveAnalyzing)
    @Test fun identifyDone() = identify(Fixtures.liveDone)

    @Test fun survey() = shot(1) { SurveyScreen(Fixtures.survey, true, {}, {}, { _, _ -> }, {}, {}, {}, {}, it) }
    @Test fun surveyEmpty() = shot(1) { SurveyScreen(Fixtures.surveyEmpty, false, {}, {}, { _, _ -> }, {}, {}, {}, {}, it) }

    @Test fun records() = shot(2) { RecordsScreen(Fixtures.records, {}, {}, {}, it) }
    @Test fun recordsEmpty() = shot(2) { RecordsScreen(emptyList(), {}, {}, {}, it) }
    @Test fun compare() = shot(2) { CompareScreen(Fixtures.compare, {}, it) }
}
