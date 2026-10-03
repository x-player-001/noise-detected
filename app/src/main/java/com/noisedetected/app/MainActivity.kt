package com.noisedetected.app

import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.noisedetected.app.data.StoredMeasurement
import com.noisedetected.app.ui.BottomNav
import com.noisedetected.app.ui.CompareScreen
import com.noisedetected.app.ui.IdentifyScreen
import com.noisedetected.app.ui.RecordsScreen
import com.noisedetected.app.ui.SurveyScreen
import com.noisedetected.app.ui.theme.AppTheme
import com.noisedetected.app.ui.theme.Palette

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        // 测量时屏幕常亮
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { AppTheme { App() } }
    }
}

/** 页面骨架：内容 + 底部导航。各页面自己处理状态栏留白。 */
@Composable
internal fun AppFrame(tab: Int, onTab: (Int) -> Unit, content: @Composable (Modifier) -> Unit) {
    Scaffold(
        containerColor = Palette.Bg,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = { BottomNav(tab, onTab) },
    ) { padding -> content(Modifier.padding(padding)) }
}

@Composable
private fun App(vm: NoiseViewModel = viewModel()) {
    val context = LocalContext.current
    val live by vm.live.collectAsStateWithLifecycle()
    val survey by vm.survey.collectAsStateWithLifecycle()
    val records by vm.records.collectAsStateWithLifecycle()
    val compare by vm.compare.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }

    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (ok) pendingAction?.invoke()
        pendingAction = null
    }
    fun withMic(action: () -> Unit) {
        if (granted) action() else {
            pendingAction = action
            launcher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    AppFrame(tab, { tab = it }) { modifier ->
        when (tab) {
            0 -> IdentifyScreen(
                state = live,
                renderer = vm.renderer,
                onStart = { withMic(vm::startIdentify) },
                onStop = vm::stop,
                onTogglePeakHold = vm::togglePeakHold,
                onSave = vm::saveMeasurement,
                onDismissMessage = vm::dismissMessage,
                modifier = modifier,
            )
            1 -> SurveyScreen(
                state = survey,
                hasIdentifiedTone = live.mainFrequencyHz != null,
                onUseIdentifiedTone = { vm.useCurrentToneAsTarget() },
                onSetTarget = vm::setTarget,
                onAddLocation = vm::addLocation,
                onMeasure = { id -> withMic { vm.measurePoint(id) } },
                onRemoveLastPoint = vm::removeLastPoint,
                onRemoveLocation = vm::removeLocation,
                onStop = vm::stop,
                modifier = modifier,
            )
            else -> {
                val items = compare
                if (items != null) {
                    BackHandler(onBack = vm::closeCompare)
                    CompareScreen(items, onBack = vm::closeCompare, modifier = modifier)
                } else {
                    RecordsScreen(
                        records = records,
                        onCompare = vm::openCompare,
                        onShare = { shareRecordings(context, it) },
                        onDelete = vm::deleteRecords,
                        modifier = modifier,
                    )
                }
            }
        }
    }
}

/** 通过系统分享面板发送 WAV（微信、网盘、邮件等），用 FileProvider 临时授权读取。 */
private fun shareRecordings(context: Context, records: List<StoredMeasurement>) {
    val uris = ArrayList(records.map { FileProvider.getUriForFile(context, "${context.packageName}.files", it.wavFile) })
    if (uris.isEmpty()) return
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
    }
    intent.type = "audio/wav"
    intent.clipData = ClipData.newRawUri(null, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, "分享录音"))
}
