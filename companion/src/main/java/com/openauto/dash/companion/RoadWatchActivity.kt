package com.openauto.dash.companion

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.BrightnessLow
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size as GeoSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.openauto.dash.roadwatch.RoadWarning
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.filled.Straighten
import com.openauto.dash.roadwatch.Mount
import kotlin.math.tan
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.TextButton

/**
 * Road watch's screen: the phone in its holder behind the windscreen,
 * sideways or upright, the camera's picture full screen with what it reads drawn over it (the lane,
 * the vehicle ahead and how far it is), a line to drag onto the horizon, and
 * a warning across the screen when no car is linked to give it. The screen
 * stays on; it can be dimmed.
 */
class RoadWatchActivity : ComponentActivity() {
    private lateinit var watch: RoadWatch
    private lateinit var frames: ExecutorService
    private var analysis: ImageAnalysis? = null
    /** The road recorder, once the camera is bound with it (the driver turned recording on). */
    private val recorder = MutableStateFlow<RoadRecorder?>(null)
    /** Gravity in the phone's axes (pointing up), for placing the phone and noticing it moved. */
    private val up = MutableStateFlow<FloatArray?>(null)
    private val gravity = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            up.value = e.values.copyOf()
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private val displays = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        // Turned in its holder (sideways, upright, either way up): no restart, so the frames are told.
        override fun onDisplayChanged(displayId: Int) {
            val rotation = ContextCompat.getDisplayOrDefault(this@RoadWatchActivity).rotation
            analysis?.targetRotation = rotation
            // The video too, or the clips recorded after the turn come out on their side.
            recorder.value?.let { it.videoCapture.targetRotation = rotation }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        watch = RoadWatch(this)
        frames = Executors.newSingleThreadExecutor()
        getSystemService(DisplayManager::class.java)?.registerDisplayListener(displays, null)
        setContent {
            CompanionTheme {
                RoadWatchScreen(
                    watch,
                    recorder,
                    up,
                    onCamera = { view -> bind(view) },
                    onDim = { dim -> dim(dim) },
                    onModel = { onFrames { watch.modelArrived() } },
                    onClose = { finish() }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        getSystemService(SensorManager::class.java)?.let { s ->
            s.getDefaultSensor(Sensor.TYPE_GRAVITY)?.let { s.registerListener(gravity, it, SensorManager.SENSOR_DELAY_UI) }
        }
        // On the frames' thread like stop, so a quick stop and start keep their order.
        onFrames { watch.start() }
        // Stopped with the screen (a call over it, the power button): the camera comes
        // back by itself, the recorder did not, and no clip was kept for the rest of the drive.
        recorder.value?.start()
    }

    override fun onStop() {
        getSystemService(SensorManager::class.java)?.unregisterListener(gravity)
        recorder.value?.stop()
        // On the frames' own thread, after the frame being read.
        onFrames { watch.stop() }
        super.onStop()
    }

    override fun onDestroy() {
        getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(displays)
        analysis?.clearAnalyzer()
        recorder.value?.close()
        frames.execute { watch.close() }
        frames.shutdown()
        super.onDestroy()
    }

    /** [work] on the frames' thread, unless the screen is already gone. */
    private fun onFrames(work: () -> Unit) {
        if (!frames.isShutdown) frames.execute(work)
    }

    private fun dim(on: Boolean) {
        window.attributes = window.attributes.apply {
            screenBrightness = if (on) 0.02f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }

    private fun bind(view: PreviewView) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = runCatching { future.get() }.getOrNull() ?: return@addListener
            val fourByThree = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                // 1280 across: a speed-limit sign is readable about twice as far off as at 640.
                .setResolutionStrategy(ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build()
            val preview = Preview.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build()
                )
                .build()
                .also { it.setSurfaceProvider(view.surfaceProvider) }
            val reader = ImageAnalysis.Builder()
                .setResolutionSelector(fourByThree)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { it.setAnalyzer(frames, watch) }
            reader.targetRotation = ContextCompat.getDisplayOrDefault(this@RoadWatchActivity).rotation
            analysis = reader
            provider.unbindAll()
            val back = CameraSelector.DEFAULT_BACK_CAMERA
            // The recorder's video beside the frames, when the driver turned recording on; some phones can't
            // run the three together, and then the road is watched without being recorded.
            val rec = if (RoadClips.enabled.value) RoadRecorder(this).also { recorder.value = it; watch.recorder = it } else null
            var camera: Camera? = rec?.let { r ->
                runCatching {
                    val group = UseCaseGroup.Builder().addUseCase(preview).addUseCase(reader).addUseCase(r.videoCapture).addEffect(r.overlay).build()
                    provider.bindToLifecycle(this@RoadWatchActivity, back, group)
                }.onFailure {
                    Log.w("RoadWatch", "no recording beside Road watch on this phone", it)
                    provider.unbindAll()
                    r.unsupported()
                }.getOrNull()
            }
            if (camera != null) {
                rec?.start()
            } else {
                camera = runCatching { provider.bindToLifecycle(this@RoadWatchActivity, back, preview, reader) }.getOrNull() ?: return@addListener
            }
            val bound = camera ?: return@addListener
            fieldOfView(bound, zoom(bound))?.let { (wide, tall) -> watch.fieldOfView(wide, tall) }
            lifecycleScope.launch { watch.view.map { it.dark }.distinctUntilChanged().collect { dark -> exposure(bound, dark) } }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * At night the camera exposes [NIGHT_EV] less than it would: the auto
     * exposure opens up for the dark road and oncoming headlights then wash
     * out the car ahead's tail lights and the lane's lines. By day, as it is.
     */
    private fun exposure(camera: Camera, dark: Boolean) {
        val state = camera.cameraInfo.exposureState
        if (!state.isExposureCompensationSupported) return
        val step = state.exposureCompensationStep.toFloat()
        if (step <= 0f) return
        val index = if (dark) (NIGHT_EV / step).roundToInt().coerceIn(state.exposureCompensationRange.lower, state.exposureCompensationRange.upper) else 0
        runCatching { camera.cameraControl.setExposureCompensationIndex(index) }
    }

    /**
     * The camera zoomed in a little ([ROAD_ZOOM]): less dashboard and sky, and
     * the road ahead in more of the sensor's detail. The ratio it ends at.
     */
    private fun zoom(camera: Camera): Float {
        val state = camera.cameraInfo.zoomState.value ?: return 1f
        val ratio = ROAD_ZOOM.coerceIn(state.minZoomRatio, state.maxZoomRatio)
        camera.cameraControl.setZoomRatio(ratio)
        RoadStats.zoom = ratio
        return ratio
    }

    /**
     * The camera's view across its long side and across its short one, at
     * [zoom]: the upright frame's width with the phone sideways, then with it upright.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun fieldOfView(camera: Camera, zoom: Float): Pair<Float, Float>? = runCatching {
        val info = Camera2CameraInfo.from(camera.cameraInfo)
        val focal = (info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return null) * zoom
        val sensor = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
        val wide = viewDegrees(max(sensor.width, sensor.height), focal) ?: return null
        val tall = viewDegrees(min(sensor.width, sensor.height), focal) ?: return null
        wide to tall
    }.getOrNull()

    private fun viewDegrees(sensorMm: Float, focalMm: Float): Float? =
        Math.toDegrees(2 * atan(sensorMm / (2.0 * focalMm))).toFloat().takeIf { it in 25f..120f }

    companion object {
        fun open(context: Context) {
            context.startActivity(Intent(context, RoadWatchActivity::class.java))
        }
    }
}

@Composable
private fun RoadWatchScreen(
    watch: RoadWatch,
    recorder: StateFlow<RoadRecorder?>,
    up: StateFlow<FloatArray?>,
    onCamera: (PreviewView) -> Unit,
    onDim: (Boolean) -> Unit,
    onModel: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }

    // The car shows the warnings: once linked, the phone's screen dims by itself, and a tap brings it back;
    // not while Road watch is experimental, when the driver wants to see what it sees.
    var dimmed by remember { mutableStateOf(false) }
    val link by LinkServer.state.collectAsState()
    val linked = link is LinkState.Connected
    val setDimmed: (Boolean) -> Unit = { on -> dimmed = on; onDim(on) }
    LaunchedEffect(linked, dimmed) {
        if (linked && !dimmed && !ROAD_WATCH_EXPERIMENTAL) {
            delay(AUTO_DIM_MS)
            setDimmed(true)
        }
    }
    // The battery, every half minute: a word when it goes down with no charger, and the recorder paused before the phone dies.
    var battery by remember { mutableStateOf<Pair<Int, Boolean>?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            val b = batteryNow(context)
            battery = b
            recorder.value?.lowPower(b != null && !b.second && b.first < RECORDER_MIN_PERCENT)
            delay(30_000L)
        }
    }

    // Placing the phone: the first time, when asked, and when it has turned in its holder (once the car stands).
    var placing by remember { mutableStateOf(watch.placedUp() == null) }
    var moved by remember { mutableStateOf(false) }
    var putOff by remember { mutableStateOf(false) }
    LaunchedEffect(placing) { watch.placing = placing }
    LaunchedEffect(Unit) {
        var turnedFor = 0
        while (true) {
            delay(1_000L)
            val saved = watch.placedUp()
            val now = up.value
            val turned = saved != null && now != null && Mount.turnedDeg(saved, now) > Mount.MOVED_DEG
            turnedFor = if (turned) turnedFor + 1 else 0
            val still = (watch.view.value.speedKmh ?: 0f) < STILL_KMH
            if (turnedFor >= MOVED_SECONDS && still && !placing && !putOff) {
                moved = true
                placing = true
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            CameraBinder(onCamera)
            RoadDrawing(watch, onTap = { if (dimmed) setDimmed(false) })
            if (placing) {
                PlacementGuide(
                    watch, up, moved,
                    onDone = { placing = false; moved = false },
                    onLater = { placing = false; putOff = true }
                )
            }
        } else {
            Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.road_camera_needed), color = Color.White)
                Spacer(Modifier.padding(6.dp))
                Button(onClick = { ask.launch(Manifest.permission.CAMERA) }) { Text(stringResource(R.string.road_allow_camera)) }
            }
        }
        RoadControls(watch, recorder, dimmed, setDimmed, battery, onModel, onClose, onPlace = { moved = false; placing = true })
    }
}

/** Turned this long, the phone has moved in its holder, not shaken over a bump. */
private const val MOVED_SECONDS = 3
/** Slower than this the car stands: the guide may come up. */
private const val STILL_KMH = 5f

/**
 * The guide to placing the phone: level, aimed with the horizon a little
 * above the middle, little dashboard and no windscreen pillar in the
 * picture, each with what to do. The horizon gravity says, over the band it
 * belongs in. All good for a few seconds, it keeps itself: the horizon to
 * start from, where the dashboard starts, and how the phone sits, to notice
 * it moved.
 */
@Composable
private fun PlacementGuide(watch: RoadWatch, up: StateFlow<FloatArray?>, moved: Boolean, onDone: () -> Unit, onLater: () -> Unit) {
    val context = LocalContext.current
    val view by watch.view.collectAsState()
    val gravity by up.collectAsState()
    val displayDeg = ContextCompat.getDisplayOrDefault(context).rotation * 90
    val reading = gravity?.let { Mount.read(it, displayDeg, view.vfovDeg) }
    val dashboardOk = view.dashboardTop?.let { 1f - it <= Mount.MAX_DASHBOARD }
    val pillarOk = view.pillar?.let { it <= Mount.MAX_PILLAR }
    val allOk = reading != null && reading.level && reading.aimed && dashboardOk != false && pillarOk != false
    val keep = {
        reading?.let { watch.setHorizon(it.horizonY) }
        view.dashboardTop?.let { if (it < 0.98f) watch.setBonnet(it - 0.01f) }
        gravity?.let { watch.placed(it.copyOf()) }
        onDone()
    }
    LaunchedEffect(allOk) {
        if (allOk) {
            delay(KEEP_AFTER_MS)
            keep()
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val w = with(density) { maxWidth.toPx() }
        val h = with(density) { maxHeight.toPx() }
        val frame = frameRect(w, h, view.frameW, view.frameH)
        Canvas(Modifier.fillMaxSize()) {
            // Where the horizon belongs, and where gravity says it is, leaning as the phone leans.
            drawRect(
                Color(0xFF2E7D32).copy(alpha = 0.18f),
                topLeft = Offset(frame.left, frame.top + Mount.HORIZON_TOP * frame.height),
                size = GeoSize(frame.width, (Mount.HORIZON_BOTTOM - Mount.HORIZON_TOP) * frame.height)
            )
            reading?.let { r ->
                val cy = frame.top + r.horizonY * frame.height
                val dy = tan(Math.toRadians(r.rollDeg.toDouble())).toFloat() * frame.width / 2
                drawLine(
                    if (r.level && r.aimed) CompanionColors.Teal else CompanionColors.Amber,
                    Offset(frame.left, cy - dy), Offset(frame.right, cy + dy), strokeWidth = 8f
                )
            }
        }
        Surface(
            color = Color.Black.copy(alpha = 0.7f), shape = RoundedCornerShape(14.dp),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp).widthIn(max = 420.dp)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(if (moved) R.string.road_place_moved else R.string.road_place_title),
                    color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold
                )
                PlaceRow(reading?.level, when {
                    reading == null || reading.level -> R.string.road_place_level_ok
                    reading.rollDeg > 0f -> R.string.road_place_level_right
                    else -> R.string.road_place_level_left
                })
                PlaceRow(reading?.aimed, when {
                    reading == null || reading.aimed -> R.string.road_place_aim_ok
                    reading.horizonY > Mount.HORIZON_BOTTOM -> R.string.road_place_aim_down
                    else -> R.string.road_place_aim_up
                })
                PlaceRow(dashboardOk, when (dashboardOk) {
                    null -> R.string.road_place_dark
                    true -> R.string.road_place_dash_ok
                    false -> R.string.road_place_dash
                })
                PlaceRow(pillarOk, when (pillarOk) {
                    null -> R.string.road_place_dark
                    true -> R.string.road_place_pillar_ok
                    false -> R.string.road_place_pillar
                })
                Text(stringResource(R.string.road_place_keeps), color = CompanionColors.Muted, style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onLater) { Text(stringResource(R.string.road_place_later)) }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = keep) { Text(stringResource(R.string.road_place_done)) }
                }
            }
        }
    }
}

/** One check of the guide: green when good, amber with what to do, grey when it can't be told. */
@Composable
private fun PlaceRow(ok: Boolean?, text: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val color = when (ok) {
            true -> CompanionColors.Teal
            false -> CompanionColors.Amber
            null -> CompanionColors.Muted
        }
        Box(Modifier.size(12.dp).background(color, CircleShape))
        Spacer(Modifier.width(10.dp))
        Text(stringResource(text), color = Color.White, style = MaterialTheme.typography.bodyMedium)
    }
}

/** All good this long, the guide keeps the placing by itself. */
private const val KEEP_AFTER_MS = 3_000L

/** Linked to a car, the screen dims after this long. */
private const val AUTO_DIM_MS = 30_000L
/** Under this, with no charger, the recorder stops so Road watch itself lasts longer. */
private const val RECORDER_MIN_PERCENT = 10
/** Under this, with no charger, the screen says to plug the phone in. */
private const val PLUG_IN_PERCENT = 20

/** The battery as a share and whether it charges, or null when Android doesn't say. */
private fun batteryNow(context: Context): Pair<Int, Boolean>? {
    val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
    val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    if (level < 0 || scale <= 0) return null
    val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL ||
        i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    return level * 100 / scale to charging
}

/** The recorder's light, top right: recording, keeping a clip, or why not. */
@Composable
private fun RecorderBadge(recorder: RoadRecorder, modifier: Modifier) {
    val state by recorder.state.collectAsState()
    val keeping by recorder.keeping.collectAsState()
    val (text, tint) = when {
        state == RoadRecorder.State.OFF -> return
        keeping > 0 -> stringResource(R.string.rec_state_keeping) to CompanionColors.Amber
        state == RoadRecorder.State.RECORDING -> stringResource(R.string.rec_state_recording) to CompanionColors.Red
        state == RoadRecorder.State.PAUSED_HOT -> stringResource(R.string.rec_state_hot) to CompanionColors.Amber
        state == RoadRecorder.State.PAUSED_FULL -> stringResource(R.string.rec_state_full) to CompanionColors.Amber
        state == RoadRecorder.State.PAUSED_LOW -> stringResource(R.string.rec_state_low) to CompanionColors.Amber
        else -> stringResource(R.string.rec_state_unsupported) to CompanionColors.Muted
    }
    Surface(color = Color.Black.copy(alpha = 0.55f), shape = RoundedCornerShape(12.dp), modifier = modifier) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(tint, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(text, color = Color.White, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** The camera's picture, bound once its view exists; the camera then follows the screen's life. */
@Composable
private fun CameraBinder(onCamera: (PreviewView) -> Unit) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx -> PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }.also(onCamera) }
    )
}

/** Where the upright frame is drawn on a [w] x [h] screen, fitted in the middle as the preview is. */
private fun frameRect(w: Float, h: Float, frameW: Int, frameH: Int): Rect {
    val scale = minOf(w / frameW, h / frameH)
    val fw = frameW * scale
    val fh = frameH * scale
    return Rect(Offset((w - fw) / 2, (h - fh) / 2), GeoSize(fw, fh))
}

@Composable
private fun RoadDrawing(watch: RoadWatch, onTap: () -> Unit) {
    val view by watch.view.collectAsState()
    // The learned horizon, or where the driver is dragging it.
    var dragged by remember { mutableStateOf<Float?>(null) }
    val horizon = dragged ?: view.band.horizonY
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = with(density) { maxWidth.toPx() }
        val h = with(density) { maxHeight.toPx() }
        val frame = frameRect(w, h, view.frameW, view.frameH)
        fun at(x: Float, y: Float) = Offset(frame.left + x * frame.width, frame.top + y * frame.height)
        val warn = view.warning
        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { onTap() } }
                .pointerInput(frame) {
                    detectVerticalDragGestures(
                        onDragStart = { dragged = watch.band.horizonY },
                        onDragEnd = { dragged?.let(watch::setHorizon); dragged = null },
                        onDragCancel = { dragged = null }
                    ) { _, dy ->
                        dragged = ((dragged ?: watch.band.horizonY) + dy / frame.height).coerceIn(0.25f, 0.7f)
                    }
                }
        ) {
            // The horizon, to drag where the road meets the sky.
            val hy = frame.top + horizon * frame.height
            drawLine(
                Color.White.copy(alpha = 0.7f), Offset(frame.left, hy), Offset(frame.right, hy), strokeWidth = 3f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 12f))
            )
            val band = view.band
            val laneColor = when (warn) {
                RoadWarning.LANE_LEFT, RoadWarning.LANE_RIGHT -> CompanionColors.Amber
                else -> CompanionColors.Teal
            }
            listOfNotNull(view.lane.left, view.lane.right).forEach { line ->
                drawLine(laneColor, at(line.topX, band.topY), at(line.bottomX, band.bonnetY), strokeWidth = 10f)
            }
            view.lead?.let { lead ->
                val b = lead.box
                val color = if (warn == RoadWarning.COLLISION || warn == RoadWarning.TOO_CLOSE) CompanionColors.Red else Color.White
                drawRect(
                    color, topLeft = at(b.left, b.top),
                    size = GeoSize(b.width * frame.width, b.height * frame.height), style = Stroke(width = 6f)
                )
            }
            if (warn == RoadWarning.COLLISION) drawRect(CompanionColors.Red.copy(alpha = 0.28f), frame.topLeft, frame.size)
        }
        view.lead?.let { lead ->
            val p = at(lead.box.left, lead.box.bottom)
            Surface(
                color = Color.Black.copy(alpha = 0.6f), shape = RoundedCornerShape(8.dp),
                modifier = Modifier.padding(
                    start = with(density) { p.x.coerceAtLeast(0f).toDp() },
                    top = with(density) { (p.y + 6f).coerceAtMost(h - 120f).toDp() }
                )
            ) {
                Text(
                    stringResource(R.string.road_lead_m, lead.distanceM.roundToInt()), color = Color.White,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }
        warn?.let { RoadWarningBanner(it, Modifier.align(Alignment.Center)) }
        val limit = view.signKmh
        when {
            limit != null -> SignBadge(limit, Modifier.align(Alignment.TopEnd).padding(12.dp))
            view.signEnd -> EndBadge(Modifier.align(Alignment.TopEnd).padding(12.dp))
        }
    }
}

/** The sign that ended the limit, drawn as the sign: a bar across an empty face. */
@Composable
private fun EndBadge(modifier: Modifier) {
    Canvas(modifier.size(64.dp).background(Color.White, CircleShape).border(3.dp, Color.Black, CircleShape)) {
        val inset = size.minDimension * 0.18f
        drawLine(Color.Black, Offset(inset, size.height - inset), Offset(size.width - inset, inset), strokeWidth = size.minDimension * 0.11f)
    }
}

/** The speed limit last read off a sign, drawn as the sign. */
@Composable
private fun SignBadge(kmh: Int, modifier: Modifier) {
    Box(
        modifier.size(64.dp).background(Color.White, CircleShape).border(7.dp, Color(0xFFD32F2F), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(kmh.toString(), color = Color.Black, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun RoadWarningBanner(warning: RoadWarning, modifier: Modifier) {
    val text = stringResource(
        when (warning) {
            RoadWarning.COLLISION -> R.string.road_warn_collision
            RoadWarning.LANE_LEFT, RoadWarning.LANE_RIGHT -> R.string.road_warn_lane
            RoadWarning.TOO_CLOSE -> R.string.road_warn_close
            RoadWarning.MOVED_OFF -> R.string.road_warn_moved_off
        }
    )
    val tint = if (warning == RoadWarning.COLLISION) CompanionColors.Red else CompanionColors.Amber
    Surface(color = tint, shape = RoundedCornerShape(18.dp), modifier = modifier) {
        Text(
            text, color = CompanionColors.Background, style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 28.dp, vertical = 14.dp)
        )
    }
}

// Kotlin's OptIn by name: this file's OptIn import is androidx's, for the Camera2 interop.
@kotlin.OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RoadControls(
    watch: RoadWatch,
    recorder: StateFlow<RoadRecorder?>,
    dimmed: Boolean,
    setDimmed: (Boolean) -> Unit,
    battery: Pair<Int, Boolean>?,
    onModel: () -> Unit,
    onClose: () -> Unit,
    onPlace: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Of the per-frame view only the three things shown here, so the controls don't recompose with every frame.
    fun shown(v: RoadView) = Triple(v.lane.lines > 0, v.speedKmh?.roundToInt(), v.horizonSet)
    val (laneSeen, speedKmh, horizonSet) = remember(watch) { watch.view.map(::shown).distinctUntilChanged() }.collectAsState(shown(watch.view.value)).value
    val rec by recorder.collectAsState()
    val link by LinkServer.state.collectAsState()
    val model by RoadModel.state.collectAsState()
    LaunchedEffect(Unit) { RoadModel.check(context) }
    Box(Modifier.fillMaxSize().padding(16.dp)) {
        // What it reads, top left: the car it warns, the lane, the vehicle finder.
        Surface(color = Color.Black.copy(alpha = 0.55f), shape = RoundedCornerShape(12.dp), modifier = Modifier.align(Alignment.TopStart)) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                val l = link
                Text(
                    if (l is LinkState.Connected) stringResource(R.string.road_linked, l.unitName) else stringResource(R.string.road_not_linked),
                    color = Color.White, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold
                )
                Text(
                    if (laneSeen) stringResource(R.string.road_lane_seen) else stringResource(R.string.road_lane_looking),
                    color = CompanionColors.Muted, style = MaterialTheme.typography.bodySmall
                )
                speedKmh?.let {
                    Text(stringResource(R.string.road_speed, it), color = CompanionColors.Muted, style = MaterialTheme.typography.bodySmall)
                }
                if (!horizonSet) Text(stringResource(R.string.road_horizon_hint), color = CompanionColors.Muted, style = MaterialTheme.typography.bodySmall)
                battery?.let { (percent, charging) ->
                    if (!charging && percent < PLUG_IN_PERCENT) {
                        Text(stringResource(R.string.road_plug_in, percent), color = CompanionColors.Amber, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        rec?.let { RecorderBadge(it, Modifier.align(Alignment.TopEnd)) }
        // The buttons wrap onto a second line on an upright phone.
        FlowRow(
            Modifier.align(Alignment.BottomEnd),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val r = rec
            val recState = r?.state?.collectAsState()?.value
            if (r != null && recState == RoadRecorder.State.RECORDING) {
                FilledTonalButton(onClick = { r.keep("button") }) {
                    Icon(Icons.Filled.Videocam, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.rec_keep))
                }
            }
            when (val m = model) {
                RoadModel.State.Ready -> Unit
                is RoadModel.State.Getting -> Text(
                    stringResource(R.string.road_model_getting, (m.done * 100).roundToInt()), color = Color.White,
                    style = MaterialTheme.typography.bodySmall
                )
                else -> FilledTonalButton(onClick = { scope.launch { if (RoadModel.get(context)) onModel() } }) {
                    Icon(Icons.Filled.DirectionsCar, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (m == RoadModel.State.Failed) R.string.road_model_retry else R.string.road_model_get))
                }
            }
            FilledTonalButton(onClick = onPlace) {
                Icon(Icons.Filled.Straighten, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.road_place_button))
            }
            FilledTonalButton(onClick = { setDimmed(!dimmed) }) {
                Icon(if (dimmed) Icons.Filled.BrightnessHigh else Icons.Filled.BrightnessLow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (dimmed) R.string.road_bright else R.string.road_dim))
            }
            Button(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.road_stop))
            }
        }
    }
}

/**
 * Road watch's zoom on the main camera. On the user's recorded drives (2026-10-26) a 1.3 zoom found the
 * car ahead at 50 to 80 m 80 % of the time against 60 %, invented half as many boxes and gave 13 false
 * collision warnings against 15, signs as readable; the lane lines came out better on some drives,
 * worse on others.
 */
private const val ROAD_ZOOM = 1.3f
/** How much less the camera exposes at night, in EV (stops). */
private const val NIGHT_EV = -0.5f
