package com.openauto.dash

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.android.filament.Box as FBox
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.SwapChain
import com.google.android.filament.Texture
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import com.google.android.filament.utils.IBLPrefilterContext
import com.google.android.filament.utils.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

private const val TAG = "Car3D"

/** Vertical field of view of the 3D camera, degrees. */
private const val FOV = 30.0

/**
 * The driver's car in 3D (the pack's model.glb, MyCarLook.kt), shared by
 * every card that shows it: the engine, the model and its lighting are made
 * once, on the main thread, and let go a minute after the last card goes.
 * Not on emulators, whose GL can't run Filament.
 */
internal object Car3D {
    private var engine: Engine? = null
    private var scene: Scene? = null
    private var assetLoader: AssetLoader? = null
    private var resourceLoader: ResourceLoader? = null
    private var asset: FilamentAsset? = null
    private var light = 0
    private var ibl: IndirectLight? = null
    private var loaded: String? = null
    private var users = 0
    private val main = Handler(Looper.getMainLooper())
    private val letGo = Runnable { destroy() }
    private var inited = false

    val supported: Boolean get() = !isEmulator

    /** The model's box (glTF space: nose +x, up +y, the car's right +z). */
    var lo = FloatArray(3); private set
    var hi = FloatArray(3); private set

    /** Each named node's middle, world space. */
    val centres = HashMap<String, FloatArray>()

    /** Hinges by name: entity and rest transform. */
    val hinges = HashMap<String, Pair<Int, FloatArray>>()

    /** The renderable nodes by name. */
    val named = HashMap<String, Int>()

    fun engine(): Engine? = engine
    fun scene(): Scene? = scene

    /** Takes the model at [file] (read off the main thread by the caller), or keeps the one in. */
    fun use(file: File, glb: ByteBuffer?): Boolean {
        main.removeCallbacks(letGo)
        users++
        val key = file.path + ":" + file.lastModified()
        if (loaded == key && asset != null) return true
        if (glb == null) return false
        return runCatching { load(glb, key) }.onFailure { Log.w(TAG, "model not loaded", it) }.isSuccess
    }

    fun loadedKey(): String? = loaded

    fun done() {
        users = max(0, users - 1)
        if (users == 0) {
            main.removeCallbacks(letGo)
            main.postDelayed(letGo, 60_000)
        }
    }

    private fun load(glb: ByteBuffer, key: String) {
        if (!inited) {
            Utils.init()
            inited = true
        }
        val t0 = SystemClock.elapsedRealtime()
        val e = engine ?: Engine.create().also { engine = it }
        val s = scene ?: e.createScene().also { scene = it; lights(e, it) }
        asset?.let { old ->
            s.removeEntities(old.entities)
            assetLoader?.destroyAsset(old)
        }
        // Nothing describes the destroyed asset while the next one loads: a model that fails
        // to load (a foreign file in a pack) otherwise left its entities to be moved, lit and
        // destroyed a second time.
        asset = null; loaded = null
        centres.clear(); hinges.clear(); named.clear()
        val loader = assetLoader ?: AssetLoader(e, UbershaderProvider(e), EntityManager.get()).also { assetLoader = it }
        val resources = resourceLoader ?: ResourceLoader(e).also { resourceLoader = it }
        val a = loader.createAsset(glb) ?: error("not a glb")
        resources.loadResources(a)
        a.releaseSourceData()
        s.addEntities(a.entities)
        asset = a
        loaded = key

        val box = a.boundingBox
        val c = box.center
        val h = box.halfExtent
        lo = floatArrayOf(c[0] - h[0], c[1] - h[1], c[2] - h[2])
        hi = floatArrayOf(c[0] + h[0], c[1] + h[1], c[2] + h[2])
        centres.clear(); hinges.clear(); named.clear()
        val tm = e.transformManager
        val rm = e.renderableManager
        val m = FloatArray(16)
        val fb = FBox()
        for (ent in a.entities) {
            val name = a.getName(ent) ?: continue
            if (name.startsWith("Hinge_")) {
                tm.getTransform(tm.getInstance(ent), m)
                hinges[name] = ent to m.copyOf()
            }
            if (rm.hasComponent(ent)) {
                named[name] = ent
                rm.getAxisAlignedBoundingBox(rm.getInstance(ent), fb)
                tm.getWorldTransform(tm.getInstance(ent), m)
                val bc = fb.center
                centres[name] = floatArrayOf(
                    m[0] * bc[0] + m[4] * bc[1] + m[8] * bc[2] + m[12],
                    m[1] * bc[0] + m[5] * bc[1] + m[9] * bc[2] + m[13],
                    m[2] * bc[0] + m[6] * bc[1] + m[10] * bc[2] + m[14]
                )
            }
        }
        Log.i(TAG, "model in ${SystemClock.elapsedRealtime() - t0} ms: ${named.size} parts, hinges ${hinges.keys}")
    }

    /** A sun and an even studio sky made here (no file), for the paint's highlights. */
    private fun lights(e: Engine, s: Scene) {
        light = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.SUN)
            .color(1f, 0.97f, 0.92f).intensity(90_000f)
            .direction(-0.45f, -0.75f, 0.35f).castShadows(false).sunAngularRadius(1.5f)
            .build(e, light)
        s.addEntity(light)

        val w = 128
        val h = 64
        val buf = ByteBuffer.allocateDirect(w * h * 16).order(ByteOrder.nativeOrder())
        val fb = buf.asFloatBuffer()
        for (y in 0 until h) {
            val el = (0.5f - (y + 0.5f) / h) * PI.toFloat()
            for (x in 0 until w) {
                val az = (x + 0.5f) / w * 2f * PI.toFloat()
                val v = if (el > 0) {
                    val k = sin(el)
                    // two soft boxes, for long highlights along the paint
                    val box = 6f * (exp(-((az - 1.2f).pow(2) / 0.05f + (el - 0.6f).pow(2) / 0.02f)) +
                        exp(-((az - 4.2f).pow(2) / 0.08f + (el - 0.45f).pow(2) / 0.03f)))
                    floatArrayOf(0.85f - 0.35f * k + box, 0.9f - 0.3f * k + box, 1f - 0.2f * k + box)
                } else {
                    val g = 0.18f - 0.13f * ((-el).coerceAtMost(0.3f) / 0.3f)
                    floatArrayOf(g, g, g * 1.05f)
                }
                fb.put(v[0]); fb.put(v[1]); fb.put(v[2]); fb.put(1f)
            }
        }
        buf.rewind()
        val equirect = Texture.Builder().width(w).height(h).levels(8)
            .usage(Texture.Usage.DEFAULT or Texture.Usage.GEN_MIPMAPPABLE)
            .sampler(Texture.Sampler.SAMPLER_2D).format(Texture.InternalFormat.RGBA16F).build(e)
        equirect.setImage(e, 0, Texture.PixelBufferDescriptor(buf, Texture.Format.RGBA, Texture.Type.FLOAT))
        equirect.generateMipmaps(e)
        val ctx = IBLPrefilterContext(e)
        val toCube = IBLPrefilterContext.EquirectangularToCubemap(ctx)
        val cube = toCube.run(equirect)
        val spec = IBLPrefilterContext.SpecularFilter(ctx)
        val refl = spec.run(cube)
        ibl = IndirectLight.Builder().reflections(refl).irradiance(1, floatArrayOf(0.55f, 0.58f, 0.62f)).intensity(28_000f).build(e)
        s.indirectLight = ibl
        e.destroyTexture(equirect)
        e.destroyTexture(cube)
        spec.destroy(); toCube.destroy(); ctx.destroy()
    }

    fun setGlow(names: Collection<String>, r: Float, g: Float, b: Float) {
        val e = engine ?: return
        val rm = e.renderableManager
        for (n in names) {
            val ent = named[n] ?: continue
            val ri = rm.getInstance(ent)
            if (ri == 0) continue
            for (p in 0 until rm.getPrimitiveCount(ri)) rm.getMaterialInstanceAt(ri, p).setParameter("emissiveFactor", r, g, b)
        }
    }

    /**
     * [names] lit in r, g, b and every other part dark. The model and its
     * materials are shared by every card showing the car: each one sets the
     * whole of its own light before it draws, so a part another card lit (the
     * door alert's open door) does not stay lit on this one.
     */
    fun setGlowOnly(names: Collection<String>, r: Float, g: Float, b: Float) {
        val e = engine ?: return
        val rm = e.renderableManager
        for ((n, ent) in named) {
            val on = n in names
            val ri = rm.getInstance(ent)
            if (ri == 0) continue
            for (p in 0 until rm.getPrimitiveCount(ri)) {
                rm.getMaterialInstanceAt(ri, p).setParameter("emissiveFactor", if (on) r else 0f, if (on) g else 0f, if (on) b else 0f)
            }
        }
    }

    /** Hinge [name] opened by [k] (0 shut .. 1 open). */
    fun setHinge(name: String, k: Float) {
        val e = engine ?: return
        val (ent, rest) = hinges[name] ?: return
        val deg = Car3DSpots.hingeAngle(name) ?: return
        // Doors swing about the vertical (glTF y); the tailgate and the bonnet about the car's width (-z).
        val r = if (name.startsWith("Hinge_door")) rotation(0f, 1f, 0f, deg * k) else rotation(0f, 0f, -1f, deg * k)
        val tm = e.transformManager
        val ti = tm.getInstance(ent)
        if (ti != 0) tm.setTransform(ti, times(rest, r))
    }

    private fun destroy() {
        if (users > 0) return
        val e = engine ?: return
        runCatching {
            asset?.let { a -> scene?.removeEntities(a.entities); assetLoader?.destroyAsset(a) }
            resourceLoader?.destroy()
            assetLoader?.destroy()
            ibl?.let { e.destroyIndirectLight(it) }
            if (light != 0) { e.destroyEntity(light); EntityManager.get().destroy(light) }
            scene?.let { e.destroyScene(it) }
            e.destroy()
        }.onFailure { Log.w(TAG, "letting go", it) }
        engine = null; scene = null; asset = null; assetLoader = null; resourceLoader = null; ibl = null; light = 0; loaded = null
        centres.clear(); hinges.clear(); named.clear()
    }
}

/** The driver's car in 3D when there is one to show (a pack with a model, the own car switched on, a real GPU). */
@Composable
internal fun rememberCar3DModel(): File? {
    val mine by MyCarLook.shown.collectAsState()
    return mine?.model?.takeIf { Car3D.supported }
}

/** One problem the 3D car turns to: where, its number and words, and how serious (2 stop, 1 garage, 0 good to know). */
internal data class Problem3D(val number: Int, val spot: Spot3D, val label: String, val serious: Int)

/** Where the camera is: the point it looks at, around which it turns. */
private data class CamPose(val tx: Float, val ty: Float, val tz: Float, val yaw: Float, val pitch: Float, val dist: Float)

/**
 * The car in 3D on the Car problems card: it turns to [selected]'s part
 * (null: the whole car), opens what hides it, lights it, and pins its number
 * there. [interactive]: a finger turns it (parked only). Drawn only while
 * something moves; [onPick] when a number chip is tapped (null: whole car).
 * [chips]: false leaves the chips out (a look showing the car as scenery).
 */
@Composable
internal fun Car3DProblems(
    model: File,
    problems: List<Problem3D>,
    selected: Problem3D?,
    interactive: Boolean,
    onPick: (Int?) -> Unit,
    modifier: Modifier = Modifier,
    /** The problem chips and Whole car down the side; off where a look shows the car as scenery. */
    chips: Boolean = true
) {
    val staged = rememberStage(model)
    val stage = staged.stage
    val ready = staged.ready
    val failed = staged.failed
    var pin by remember { mutableStateOf<Offset?>(null) }
    stage.onPin = { pin = it }
    LaunchedEffect(selected, ready) { if (ready) stage.show(selected) }

    val tone = when (selected?.serious) { 2 -> DashColors.Critical; 1 -> DashColors.Warning; else -> DashColors.Accent }
    val pick by rememberUpdatedState(onPick)
    val density = LocalDensity.current

    BoxWithConstraints(modifier.clip(DashShape.Medium)) {
        // The stage: a soft spot on a dark floor behind the car.
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.radialGradient(listOf(DashColors.CardHi.copy(alpha = 0.9f), Color.Transparent), Offset(size.width / 2, size.height * 0.4f), size.maxDimension * 0.6f))
        }
        if (!failed) {
            AndroidView(
                factory = { TextureView(it).also(stage::attach) },
                modifier = Modifier.fillMaxSize().then(
                    if (interactive) Modifier.pointerInput(stage) {
                        detectDragGestures { change, drag -> change.consume(); stage.drag(drag.x, drag.y) }
                    } else Modifier
                )
            )
        }
        val at = pin
        if (selected != null && at != null && ready) {
            Canvas(Modifier.fillMaxSize()) {
                val r = 12.dp.toPx()
                drawCircle(Brush.radialGradient(listOf(tone.copy(alpha = 0.5f), tone.copy(alpha = 0.15f), Color.Transparent), at, r * 4f), r * 4f, at)
                drawCircle(tone.copy(alpha = 0.35f), r * 2.1f, at, style = Stroke(2.dp.toPx()))
                drawCircle(Color.Black.copy(alpha = 0.4f), r * 1.25f, at)
                drawCircle(tone, r, at)
            }
            // The number on the pin, and the problem's words beside it.
            val x = with(density) { at.x.toDp() }
            val y = with(density) { at.y.toDp() }
            Box(
                Modifier.offset { IntOffset((at.x - 12.dp.toPx()).roundToInt(), (at.y - 12.dp.toPx()).roundToInt()) }.size(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    selected.number.toString(),
                    color = if (selected.serious == 2) Color.White else Color(0xFF111111),
                    fontWeight = FontWeight.Black,
                    style = MaterialTheme.typography.labelLarge
                )
            }
            val right = x < maxWidth * 0.55f
            val calloutX = if (right) x + 34.dp else (x - 34.dp - 220.dp).coerceAtLeast(8.dp)
            val calloutY = (y + 18.dp).coerceAtMost(maxHeight - 64.dp).coerceAtLeast(8.dp)
            Column(
                Modifier
                    .offset(calloutX, calloutY)
                    .widthIn(max = 220.dp)
                    .clip(DashShape.Small)
                    .background(DashColors.Card.copy(alpha = 0.82f))
                    .border(1.dp, tone.copy(alpha = 0.5f), DashShape.Small)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(selected.label, color = DashColors.TextPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        // One chip per problem, and the whole car.
        if (chips) Column(Modifier.align(Alignment.TopStart).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (p in problems) {
                Chip3D(stringResource(R.string.car3d_problem, p.number), selected?.number == p.number) { pick(p.number) }
            }
            Chip3D(stringResource(R.string.car3d_whole_car), selected == null) { pick(null) }
        }
        if (interactive && ready) {
            Text(
                stringResource(R.string.car3d_drag_hint),
                color = DashColors.Muted,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
            )
        }
    }
}

/** A [Stage] with [model] in it, and whether the model is in yet ([ready]) or could not be ([failed]). */
private class Staged(val stage: Stage, val ready: Boolean, val failed: Boolean)

/** A view of the shared 3D car: the model read in, the stage paused, resumed and let go with the card. */
@Composable
private fun rememberStage(model: File): Staged {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var ready by remember(model) { mutableStateOf(false) }
    var failed by remember(model) { mutableStateOf(false) }
    val stage = remember { Stage(context) }

    LaunchedEffect(model) {
        if (stage.holding) { stage.holding = false; Car3D.done() }
        val key = model.path + ":" + model.lastModified()
        val glb = if (Car3D.loadedKey() == key) null else withContext(Dispatchers.IO) {
            runCatching {
                val bytes = model.readBytes()
                ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes).also { it.rewind() }
            }.getOrNull()
        }
        if (Car3D.use(model, glb)) {
            stage.holding = true
            ready = true
            stage.start()
        } else {
            Car3D.done()
            failed = true
        }
    }
    DisposableEffect(lifecycle, stage) {
        val obs = LifecycleEventObserver { _, ev ->
            when (ev) {
                Lifecycle.Event.ON_RESUME -> stage.resume()
                Lifecycle.Event.ON_PAUSE -> stage.pause()
                else -> Unit
            }
        }
        lifecycle.lifecycle.addObserver(obs)
        onDispose {
            lifecycle.lifecycle.removeObserver(obs)
            stage.dispose()
            if (stage.holding) Car3D.done()
        }
    }
    return Staged(stage, ready, failed)
}

/**
 * The driver's car in 3D with what [open] says is open (fl, fr, rl, rr,
 * tailgate, bonnet) swinging open on its hinges, the camera turning to it
 * ([Car3DSpots.opened]): the door alert's large designs. [fallback] until the
 * model is in, and instead when it can't be shown or hasn't the hinges of
 * what is open (a model drawn on the phone has none).
 */
@Composable
internal fun Car3DOpen(model: File, open: Set<String>, modifier: Modifier = Modifier, fallback: @Composable () -> Unit) {
    val staged = rememberStage(model)
    val spot = remember(open) { Car3DSpots.opened(open) }
    val hinged = staged.ready && spot.open.all { it in Car3D.hinges }
    LaunchedEffect(spot, hinged) { if (hinged) staged.stage.show(Problem3D(0, spot, "", serious = 1)) }
    Box(modifier) {
        if (!hinged) fallback()
        if (!staged.failed && (hinged || !staged.ready)) {
            AndroidView(factory = { TextureView(it).also(staged.stage::attach) }, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun Chip3D(text: String, on: Boolean, onClick: () -> Unit) {
    Text(
        text,
        color = if (on) DashColors.OnAccent else DashColors.TextSecondary,
        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier
            .clip(DashShape.Pill)
            .background(if (on) DashColors.Accent else DashColors.Card.copy(alpha = 0.8f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

/**
 * One card's view of the shared car: its own surface, camera and renderer,
 * frames only while the camera, a hinge or a glow moves.
 */
private class Stage(context: Context) : Choreographer.FrameCallback {
    private val ui = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK).apply { isOpaque = false }
    private var renderer: Renderer? = null
    private var view: View? = null
    private var camera: Camera? = null
    private var cameraEntity = 0
    private var swapChain: SwapChain? = null
    private var windowSurface: Surface? = null
    private var width = 0
    private var height = 0
    private val choreographer = Choreographer.getInstance()
    private var posted = false
    private var resumed = true
    private var disposed = false
    var holding = false
    var onPin: (Offset?) -> Unit = {}

    private var pose: CamPose? = null
    private var from: CamPose? = null
    private var to: CamPose? = null
    private var moveStart = 0L
    private val moveMs = 1300L
    private var target: FloatArray? = null

    private var glowing: List<String> = emptyList()
    private var glowColour = floatArrayOf(1f, 0.1f, 0.05f)
    private var glowStrength = 1f
    private var glowStart = 0L

    /** Each hinge's opening now and where it goes. */
    private val hingeFrom = HashMap<String, Float>()
    private val hingeTo = HashMap<String, Float>()
    private var hingeStart = 0L
    private val hingeMs = 1100L

    private var owed = true

    fun attach(tv: TextureView) {
        tv.isOpaque = false
        ui.renderCallback = object : UiHelper.RendererCallback {
            override fun onNativeWindowChanged(surface: Surface) {
                windowSurface = surface
                val e = Car3D.engine() ?: return
                if (renderer == null) return
                swapChain?.let { e.destroySwapChain(it) }
                swapChain = e.createSwapChain(surface, ui.swapChainFlags)
                wake()
            }
            override fun onDetachedFromSurface() {
                windowSurface = null
                val e = Car3D.engine() ?: return
                swapChain?.let { e.destroySwapChain(it); e.flushAndWait() }
                swapChain = null
            }
            override fun onResized(w: Int, h: Int) {
                width = w; height = h
                view?.viewport = Viewport(0, 0, w, h)
                camera?.setProjection(FOV, w.toDouble() / max(1, h), 0.05, 100.0, Camera.Fov.VERTICAL)
                // Where the whole car sits depends on the shape of the view.
                to?.let { if (target == null) { to = whole(); pose = to } }
                wake()
            }
        }
        ui.attachTo(tv)
    }

    /** Once the model is in: the engine's parts for this view. */
    fun start() {
        val e = Car3D.engine() ?: return
        if (renderer != null) return
        renderer = e.createRenderer().apply {
            clearOptions = Renderer.ClearOptions().apply { clear = true; clearColor = doubleArrayOf(0.0, 0.0, 0.0, 0.0) }
        }
        cameraEntity = EntityManager.get().create()
        camera = e.createCamera(cameraEntity).apply {
            setExposure(16f, 1f / 125f, 100f)
            if (width > 0) setProjection(FOV, width.toDouble() / max(1, height), 0.05, 100.0, Camera.Fov.VERTICAL)
        }
        view = e.createView().apply {
            scene = Car3D.scene()
            camera = this@Stage.camera
            blendMode = View.BlendMode.TRANSLUCENT
            setShadowingEnabled(false)
            multiSampleAntiAliasingOptions = View.MultiSampleAntiAliasingOptions().apply { enabled = true; sampleCount = 4 }
            ambientOcclusionOptions = View.AmbientOcclusionOptions().apply { enabled = false }
            bloomOptions = View.BloomOptions().apply { enabled = false }
            renderQuality = View.RenderQuality().apply { hdrColorBuffer = View.QualityLevel.LOW }
            if (width > 0) viewport = Viewport(0, 0, width, height)
        }
        // A surface that came before the model gets its swap chain now.
        if (swapChain == null) windowSurface?.let { s -> swapChain = e.createSwapChain(s, ui.swapChainFlags) }
        pose = whole(); to = pose; from = pose
        wake()
    }

    /**
     * The whole car, filling the view: the camera backs off just enough for
     * every corner of the car's box to be inside the picture.
     */
    private fun whole(): CamPose {
        val lo = Car3D.lo
        val hi = Car3D.hi
        val c = Car3DSpots.WHOLE
        val t = floatArrayOf((lo[0] + hi[0]) / 2, (lo[1] + hi[1]) / 2, (lo[2] + hi[2]) / 2)
        val yaw = Math.toRadians(c.yaw.toDouble())
        val pitch = Math.toRadians(c.pitch.toDouble())
        // From the target toward the eye, and the camera's own axes.
        val back = floatArrayOf((cos(pitch) * cos(yaw)).toFloat(), sin(pitch).toFloat(), (cos(pitch) * sin(yaw)).toFloat())
        val f = floatArrayOf(-back[0], -back[1], -back[2])
        val r = norm(cross(f, floatArrayOf(0f, 1f, 0f)))
        val u = cross(r, f)
        val aspect = if (height > 0) width.toFloat() / height else 1.6f
        val tanV = tan(Math.toRadians(FOV / 2)).toFloat()
        val tanH = tanV * aspect
        var dist = 0f
        for (i in 0 until 8) {
            val corner = floatArrayOf(
                (if (i and 1 == 0) lo[0] else hi[0]) - t[0],
                (if (i and 2 == 0) lo[1] else hi[1]) - t[1],
                (if (i and 4 == 0) lo[2] else hi[2]) - t[2]
            )
            val z = dot(corner, f)
            dist = max(dist, kotlin.math.abs(dot(corner, r)) / tanH - z)
            dist = max(dist, kotlin.math.abs(dot(corner, u)) / tanV - z)
        }
        // A car is rounder than its box: its corners can go a little past the edges.
        return CamPose(t[0], t[1], t[2], c.yaw, c.pitch, dist * 0.94f)
    }

    /** Turns to [p] (null: the whole car), opens what hides it, lights it. */
    fun show(p: Problem3D?) {
        if (renderer == null) return
        val now = SystemClock.uptimeMillis()
        // Lights out on the last part, open parts close unless the new one needs them.
        Car3D.setGlow(glowing, 0f, 0f, 0f)
        glowing = emptyList()
        val wanted = p?.spot?.open.orEmpty().flatMap { h -> Car3D.hinges.keys.filter { it == h || (h.endsWith("_") && it.startsWith(h)) } }.toSet()
        for (h in Car3D.hinges.keys) {
            hingeFrom[h] = hingeNow(h, now)
            hingeTo[h] = if (h in wanted) 1f else 0f
        }
        hingeStart = now
        from = pose
        if (p == null) {
            target = null
            to = whole()
        } else {
            val s = p.spot
            val parts = Car3D.named.keys.filter { Car3DSpots.matches(it, s.glow) }
            val point = if (parts.isNotEmpty()) {
                val cs = parts.mapNotNull { Car3D.centres[it] }
                floatArrayOf(cs.map { it[0] }.average().toFloat(), cs.map { it[1] }.average().toFloat(), cs.map { it[2] }.average().toFloat())
            } else {
                val lo = Car3D.lo
                val hi = Car3D.hi
                val (along, across, up) = s.at
                floatArrayOf(hi[0] - along * (hi[0] - lo[0]), lo[1] + up * (hi[1] - lo[1]), (lo[2] + hi[2]) / 2 + across * (hi[2] - lo[2]) / 2)
            }
            target = point
            // Close to the part, as far from it as a share of the car's length.
            val length = Car3D.hi[0] - Car3D.lo[0]
            to = CamPose(point[0], point[1], point[2], s.camera.yaw, s.camera.pitch, length * s.camera.zoom)
            glowing = parts
            glowStrength = s.strength
            // A lamp glows its own colour (the pin carries how serious it is), anything else the pin's.
            glowColour = when {
                parts.isNotEmpty() && parts.all { it.startsWith("Lamp_tail") } -> floatArrayOf(0.75f, 0.05f, 0.03f)
                parts.isNotEmpty() && parts.all { it.startsWith("Lamp_head") } -> floatArrayOf(0.7f, 0.62f, 0.45f)
                p.serious == 2 -> floatArrayOf(0.8f, 0.06f, 0.03f)
                p.serious == 1 -> floatArrayOf(0.8f, 0.42f, 0.06f)
                else -> floatArrayOf(0.4f, 0.55f, 0.8f)
            }
        }
        moveStart = now
        glowStart = now + moveMs
        wake()
    }

    private fun hingeNow(h: String, now: Long): Float {
        val k = smooth(((now - hingeStart).toFloat() / hingeMs).coerceIn(0f, 1f))
        val a = hingeFrom[h] ?: 0f
        val b = hingeTo[h] ?: 0f
        return a + (b - a) * k
    }

    fun drag(dx: Float, dy: Float) {
        val p = pose ?: return
        val moved = p.copy(yaw = p.yaw - dx * 0.3f, pitch = (p.pitch + dy * 0.2f).coerceIn(-5f, 80f))
        pose = moved; from = moved; to = moved; moveStart = 0
        wake()
    }

    fun resume() { resumed = true; wake() }
    fun pause() { resumed = false; unpost() }

    fun dispose() {
        disposed = true
        unpost()
        // What this card lit goes dark with it.
        Car3D.setGlow(glowing, 0f, 0f, 0f)
        glowing = emptyList()
        ui.detach()
        val e = Car3D.engine() ?: return
        swapChain?.let { e.destroySwapChain(it) }
        view?.let { e.destroyView(it) }
        renderer?.let { e.destroyRenderer(it) }
        if (cameraEntity != 0) { e.destroyCameraComponent(cameraEntity); EntityManager.get().destroy(cameraEntity) }
        swapChain = null; view = null; renderer = null; camera = null
    }

    private fun wake() { owed = true; post() }

    private fun post() {
        if (!resumed || disposed || posted) return
        posted = true
        choreographer.postFrameCallback(this)
    }

    private fun unpost() {
        if (posted) { choreographer.removeFrameCallback(this); posted = false }
    }

    private var lastDraw = 0L
    private var wasMoving = false

    override fun doFrame(frameTimeNanos: Long) {
        posted = false
        if (disposed || !resumed) return
        val now = SystemClock.uptimeMillis()
        val moving = moveStart > 0 && now - moveStart < moveMs
        val opening = now - hingeStart < hingeMs
        // The light pulses three times (2.4 s), then stays on: nothing left to draw.
        val pulsing = glowing.isNotEmpty() && now < glowStart + 2400
        // The frame the camera arrives on is drawn too: the pin waits for it.
        if (wasMoving && !moving) owed = true
        wasMoving = moving
        if (!moving && !opening && !pulsing && !owed) return
        // Nothing to draw into yet (no surface, or a card whose model has none of the hinges it
        // asks for and never attaches one): the surface's arrival wakes the stage again, so no
        // frame is posted meanwhile; otherwise every hinge and glow was set at vsync for nothing.
        if (swapChain == null || !ui.isReadyToRender) return
        // A pulse alone needs no more than 30 images a second.
        if (!moving && !opening && !owed && frameTimeNanos - lastDraw < 30_000_000L) { post(); return }
        val a = from
        val b = to
        if (a != null && b != null) pose = if (moving) lerp(a, b, smooth((now - moveStart).toFloat() / moveMs)) else b
        for (h in Car3D.hinges.keys) Car3D.setHinge(h, hingeNow(h, now))
        // This card's light, and no other card's: the parts are shared.
        val k = when {
            glowing.isEmpty() || now < glowStart -> 0f
            pulsing -> 0.55f + 0.45f * cos((now - glowStart) / 800f * 2f * PI.toFloat())
            else -> 1f
        } * glowStrength
        Car3D.setGlowOnly(glowing, glowColour[0] * k, glowColour[1] * k, glowColour[2] * k)
        if (draw(frameTimeNanos)) {
            lastDraw = frameTimeNanos
            owed = false
        }
        // The pin follows the part; while the camera travels it waits.
        onPin(if (moving || target == null) null else project(target!!))
        if (moving || opening || pulsing || owed) post()
    }

    private fun draw(frameTimeNanos: Long): Boolean {
        val sc = swapChain ?: return false
        val r = renderer ?: return false
        val v = view ?: return false
        val c = camera ?: return false
        val p = pose ?: return false
        val (ex, ey, ez) = eye(p)
        c.lookAt(ex.toDouble(), ey.toDouble(), ez.toDouble(), p.tx.toDouble(), p.ty.toDouble(), p.tz.toDouble(), 0.0, 1.0, 0.0)
        if (!ui.isReadyToRender || width == 0) return false
        if (!r.beginFrame(sc, frameTimeNanos)) return false
        r.render(v)
        r.endFrame()
        return true
    }

    private fun eye(p: CamPose): Triple<Float, Float, Float> {
        val yaw = Math.toRadians(p.yaw.toDouble())
        val pitch = Math.toRadians(p.pitch.toDouble())
        return Triple(
            (p.tx + p.dist * cos(pitch) * cos(yaw)).toFloat(),
            (p.ty + p.dist * sin(pitch)).toFloat(),
            (p.tz + p.dist * cos(pitch) * sin(yaw)).toFloat()
        )
    }

    /** Where world point [w] lands on the view, pixels; null behind the camera. */
    private fun project(w: FloatArray): Offset? {
        val p = pose ?: return null
        if (width == 0 || height == 0) return null
        val (ex, ey, ez) = eye(p)
        val f = norm(floatArrayOf(p.tx - ex, p.ty - ey, p.tz - ez))
        val r = norm(cross(f, floatArrayOf(0f, 1f, 0f)))
        val u = cross(r, f)
        val d = floatArrayOf(w[0] - ex, w[1] - ey, w[2] - ez)
        val z = dot(d, f)
        if (z <= 0.01f) return null
        val t = tan(Math.toRadians(FOV / 2)).toFloat()
        val aspect = width.toFloat() / height
        val nx = dot(d, r) / (z * t * aspect)
        val ny = dot(d, u) / (z * t)
        return Offset((nx + 1f) / 2f * width, (1f - ny) / 2f * height)
    }
}

private fun smooth(k: Float): Float { val c = k.coerceIn(0f, 1f); return c * c * (3 - 2 * c) }

private fun lerp(a: CamPose, b: CamPose, k: Float): CamPose {
    var dy = (b.yaw - a.yaw) % 360f
    if (dy > 180) dy -= 360f
    if (dy < -180) dy += 360f
    fun l(x: Float, y: Float) = x + (y - x) * k
    // Out a little mid-way, so a turn round the car doesn't cut through it.
    val out = 1f + 0.35f * sin(k * PI.toFloat()) * (kotlin.math.abs(dy) / 180f)
    return CamPose(l(a.tx, b.tx), l(a.ty, b.ty), l(a.tz, b.tz), a.yaw + dy * k, l(a.pitch, b.pitch), l(a.dist, b.dist) * out)
}

private fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
private fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
private fun norm(a: FloatArray): FloatArray { val l = sqrt(dot(a, a)).coerceAtLeast(1e-6f); return floatArrayOf(a[0] / l, a[1] / l, a[2] / l) }

/** Column-major rotation about a unit axis. */
private fun rotation(x: Float, y: Float, z: Float, deg: Float): FloatArray {
    val a = Math.toRadians(deg.toDouble())
    val c = cos(a).toFloat(); val s = sin(a).toFloat(); val t = 1 - c
    return floatArrayOf(
        t * x * x + c, t * x * y + s * z, t * x * z - s * y, 0f,
        t * x * y - s * z, t * y * y + c, t * y * z + s * x, 0f,
        t * x * z + s * y, t * y * z - s * x, t * z * z + c, 0f,
        0f, 0f, 0f, 1f
    )
}

/** a × b, column-major. */
private fun times(a: FloatArray, b: FloatArray): FloatArray {
    val r = FloatArray(16)
    for (col in 0 until 4) for (row in 0 until 4) {
        var s = 0f
        for (k in 0 until 4) s += a[k * 4 + row] * b[col * 4 + k]
        r[col * 4 + row] = s
    }
    return r
}
