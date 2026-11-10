package com.openauto.dash.display

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.openauto.dash.link.ClusterFace
import com.openauto.dash.link.ClusterState
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.RadialGradientPaint
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.geom.Arc2D
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The pictures the display draws itself, with plain Java2D: the idle screen,
 * the pairing code, and the cluster it shows from [ClusterState] when the head
 * unit sends data instead of video. Big type, flat colours and strokes of at
 * least 2 px, so it stays legible on a composite (RCA) monitor too.
 */
class Painter(val width: Int, val height: Int, private val overscanPct: Int, private val logo: BufferedImage? = null) {
    /** Drawn turned 180° (Rotation). */
    @Volatile var upsideDown = false

    /** The time zone the clocks are written in: the head unit's (LocalClock). */
    @Volatile var zone: ZoneId = ZoneId.systemDefault()

    /** The map's tiles by day and by night (null: none; by night the day's are dimmed). */
    @Volatile var tiles: TileSource? = null
    @Volatile var nightTiles: TileSource? = null

    /** The route the navigation follows, lat, lon pairs flattened (ClusterRoute); empty without one. */
    @Volatile var route: List<Double> = emptyList()

    /** The cover of the track playing, by the key the readings name ([ClusterState.Media.artKey]). */
    @Volatile var coverArt: Pair<String, BufferedImage>? = null

    /** The buildings' shapes for the tilted map (null: none, the tiles' flat ones only). */
    @Volatile var buildings: BuildingSource? = null

    /** The last picture had the tilted map following a car on the move: the next one would show it further on. */
    @Volatile var chaseMoving = false
        private set

    private val chase = ChaseMap()

    // The last cluster page but for the car's place (and its clock text), and where its map slot was:
    // the next picture of the same page only draws that slot again.
    private var lastRest: Any? = null
    private var mapBox: Rectangle? = null

    /** What the tilted map's pictures cost since the last call, by part; null when none was drawn (ChaseMap.takeStats). */
    fun takeMapStats(): String? = chase.takeStats()

    /** The readings drawn next came in at [ns] (System.nanoTime): the map dates their GPS fix from it. */
    fun readingsArrived(ns: Long) {
        chase.receivedNs = ns
    }


    /** TYPE_INT_RGB is 0x00RRGGBB per pixel: little-endian, the bytes are B,G,R,x (GStreamer's "bgrx"). */
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)

    private val inset = (min(width, height) * overscanPct / 100.0).roundToInt()
    private val unit = min(width, height) / 100f

    /**
     * Nothing to show yet: the clock, a status line, and the pairing code while it is on offer.
     * [clock12]: the clock as the head unit last asked for it.
     */
    fun paintIdle(name: String, status: String, now: Long, pairingUri: String?, clock12: Boolean = false) = draw { g ->
        chaseMoving = false
        unchanged = false
        lastWhole = null
        lastRest = null
        mapBox = null
        mono = false
        g.color = BG_NIGHT
        g.fillRect(0, 0, width, height)
        val logo = logo
        if (pairingUri == null && logo != null) {
            // The boot picture again, where Plymouth left it (same size, same background):
            // the hand-over can't be seen, and it stays until the head unit sends its own.
            // Only the clock comes in over it.
            val scale = min(1.0, min(width * 0.5 / logo.width, height * 0.6 / logo.height))
            val w = (logo.width * scale).roundToInt()
            val h = (logo.height * scale).roundToInt()
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(logo, (width - w) / 2, (height - h) / 2, w, h, null)
            // The time in the top right corner, clear of the logo (at most 60 % of the height, centred).
            g.color = TEXT
            g.font = font(Font.BOLD, 12f)
            drawRight(g, clockText(now, clock12, zone), width - inset - (6 * unit).roundToInt(), inset + (16 * unit).roundToInt())
            g.color = MUTED
            g.font = font(Font.PLAIN, 4.5f)
            drawCentered(g, status, width / 2, height - inset - (8 * unit).roundToInt())
            return@draw
        }
        val left = inset + (6 * unit).roundToInt()
        g.color = TEXT
        g.font = font(Font.BOLD, 22f)
        g.drawString(clockText(now, clock12, zone), left, inset + (26 * unit).roundToInt())
        g.color = MUTED
        g.font = font(Font.PLAIN, 6f)
        g.drawString(name, left, inset + (36 * unit).roundToInt())
        g.drawString(status, left, height - inset - (8 * unit).roundToInt())
        if (pairingUri != null) {
            val size = min(height - 2 * inset - (20 * unit).roundToInt(), width / 2 - inset)
            val x = width - inset - size - (4 * unit).roundToInt()
            val y = inset + (6 * unit).roundToInt()
            drawQr(g, pairingUri, x, y, size)
            g.color = MUTED
            g.font = font(Font.PLAIN, 4.5f)
            g.drawString(Words.scanApp, x, y + size + (6 * unit).roundToInt())
        }
    }

    /** The last [paintCluster] found nothing changed and left the picture as it was. */
    @Volatile var unchanged = false
        private set
    private var lastWhole: Any? = null

    /**
     * The cluster from data, on the page the head unit chose. [now] is the head unit's clock.
     * [mayReuse]: a redraw only for the clock's tick; the same page with the clock's words the
     * same and the map at rest is then left as it is ([unchanged]).
     */
    fun paintCluster(state: ClusterState, now: Long, mayReuse: Boolean = false): BufferedImage {
        val whole = Triple(state.copy(clock = 0), ClusterText(state).clock(now, zone), coverArt?.first)
        unchanged = mayReuse && whole == lastWhole && !chaseMoving
        lastWhole = whole
        return if (unchanged) image else paintClusterNow(state, now)
    }

    private fun paintClusterNow(state: ClusterState, now: Long) = draw { g ->
        paintClusterBody(g, state, now)
        // Over the page, never over an alert: the steering wheel's dashboard buttons move this screen.
        if (state.alert == null) paintFocus(g, state)
    }

    private fun paintClusterBody(g: Graphics2D, state: ClusterState, now: Long) {
        chaseMoving = false
        val design = Design.of(state.design)
        val p = Palette(state.night, Color(state.accent.toInt(), false), design)
        mono = design == Design.RETRO
        val text = ClusterText(state)
        // The tilted map moving on with nothing else changed: only its slot is drawn again,
        // the rest of the page stays as it was (most of a picture's time on a Pi 3B).
        val rest = state.copy(clock = 0, position = null) to text.clock(now, zone)
        val box = mapBox
        val mapOnly = box != null && rest == lastRest && state.alert == null
        lastRest = rest
        if (mapOnly && box != null) {
            g.color = p.bg
            g.fillRect(box.x, box.y, box.width, box.height)
            paintFace(g, ClusterFace.MAP, box, state, text, p, design, rest.second)
            return
        }
        mapBox = null
        g.color = p.bg
        g.fillRect(0, 0, width, height)
        val left = inset + (5 * unit).roundToInt()
        val right = width - inset - (5 * unit).roundToInt()
        val top = inset + (5 * unit).roundToInt()
        val bottom = height - inset - (5 * unit).roundToInt()

        // An alert up on the head unit takes the whole screen while it lasts.
        state.alert?.let { alert ->
            paintAlert(g, alert, text.clock(now, zone), left, right, top, p)
            return
        }

        // Top line: the clock, and what the car is warning about in an amber pill. A board
        // page shows no clock there (a Clock slot is for that), and without a warning the
        // line is left out altogether: the board then takes the whole height.
        val clockFont = font(Font.BOLD, 7f)
        val clock = text.clock(now, zone)
        val clockBase = top + (6 * unit).roundToInt()
        val clockOnBoard = state.layout != null
        val topLine = !clockOnBoard || text.open != null
        if (!clockOnBoard) {
            g.color = p.fg
            g.font = clockFont
            g.drawString(clock, left, clockBase)
        }
        text.open?.let { open ->
            g.font = font(Font.BOLD, 4.8f)
            // A long list of doors is cut short of the clock, never drawn over it.
            val padX = (3 * unit).roundToInt()
            val room = right - left - (if (clockOnBoard) 0 else g.getFontMetrics(clockFont).stringWidth(clock) + (6 * unit).roundToInt()) - 2 * padX
            val shown = clipped(g, open, room)
            val w = g.fontMetrics.stringWidth(shown) + 2 * padX
            val h = (8 * unit).roundToInt()
            val y = clockBase - (6.5f * unit).roundToInt()
            g.color = p.warn
            g.fillRoundRect(right - w, y, w, h, h, h)
            g.color = p.bg
            drawCentered(g, shown, right - w / 2, y + h / 2 + g.fontMetrics.ascent * 2 / 5)
        }

        val bodyTop = if (topLine) top + (12 * unit).roundToInt() else top
        val body = Rectangle(left, bodyTop, right - left, bottom - bodyTop)
        // The page as the driver laid it out on the head unit's board, when it says so.
        state.layout?.let { layout ->
            val boxes = Board.boxes(layout.arrangement, body, (3 * unit).roundToInt())
            if (boxes != null) {
                boxes.forEachIndexed { i, box ->
                    val face = layout.faces.getOrNull(i).orEmpty()
                    if (face == ClusterFace.MAP && mapBox == null) mapBox = box
                    paintFace(g, face, box, state, text, p, design, clock)
                }
                return
            }
        }
        when (state.page) {
            "MEDIA" -> paintMedia(g, state, text, body, p)
            "NAV" -> paintNav(g, state, text, body, p)
            "OBD" -> paintMeasures(g, state, text, body, p)
            else -> when (design) {
                Design.DIALS -> paintDials(g, state, text, body, p)
                Design.LARGE -> paintLarge(g, state, text, body, p)
                Design.RETRO -> paintRetro(g, state, text, body, p)
                Design.CARDS -> paintDrive(g, state, text, body, p)
            }
        }
    }

    /**
     * The steering wheel's focus on this screen ([ClusterState.focused]): a dot
     * in the top right corner, clear of the doors' pill, and a frame round the
     * screen for a moment when the focus has just come here.
     */
    private fun paintFocus(g: Graphics2D, s: ClusterState) {
        if (!s.focused) return
        val p = Palette(s.night, Color(s.accent.toInt(), false), Design.of(s.design))
        g.color = p.accent
        val dot = (2.4f * unit).roundToInt().coerceAtLeast(6)
        val margin = inset + (2 * unit).roundToInt()
        g.fillOval(width - margin - dot, margin, dot, dot)
        if (s.focusFlash) {
            val frame = (1.2f * unit).roundToInt().coerceAtLeast(2)
            g.stroke = BasicStroke(frame.toFloat())
            g.drawRoundRect(inset + frame, inset + frame, width - 2 * (inset + frame), height - 2 * (inset + frame), (8 * unit).roundToInt(), (8 * unit).roundToInt())
        }
    }

    /** [alert] full screen: a wash and a frame in its colour, a sign, its title big, its detail under it. */
    private fun paintAlert(g: Graphics2D, alert: ClusterState.Alert, clock: String, left: Int, right: Int, top: Int, p: Palette) {
        val tone = when (alert.level) {
            ClusterState.Alert.CRITICAL -> p.critical
            ClusterState.Alert.WARN -> p.warn
            else -> p.accent
        }
        // The alert's colour washed over the background, strongest in the middle.
        g.paint = RadialGradientPaint(
            width / 2f, height / 2f, max(width, height) * 0.6f, floatArrayOf(0f, 1f),
            arrayOf(mix(p.bg, tone, if (p.night) 0.26f else 0.16f), mix(p.bg, tone, if (p.night) 0.04f else 0.03f))
        )
        g.fillRect(0, 0, width, height)
        g.color = p.muted
        g.font = font(Font.BOLD, 7f)
        g.drawString(clock, left, top + (6 * unit).roundToInt())
        // A frame in the alert's colour, inside the overscan.
        val frame = (1.5f * unit).roundToInt().coerceAtLeast(2)
        g.color = tone
        g.stroke = BasicStroke(frame.toFloat())
        g.drawRoundRect(inset + frame, inset + frame, width - 2 * (inset + frame), height - 2 * (inset + frame), (8 * unit).roundToInt(), (8 * unit).roundToInt())

        val cx = width / 2
        val room = right - left
        warningSign(g, cx.toFloat(), height * 0.5f - 34 * unit, 22 * unit, tone, p.bg)
        g.color = p.fg
        g.font = font(Font.BOLD, 15f)
        drawCentered(g, clipped(g, alert.title, room), cx, (height * 0.5f + 8 * unit).roundToInt())
        if (alert.detail.isNotEmpty()) {
            g.color = tone
            g.font = font(Font.BOLD, 7.5f)
            drawCentered(g, clipped(g, alert.detail, room), cx, (height * 0.5f + 22 * unit).roundToInt())
        }
    }

    /**
     * The speed in a ring that fills with it, the car's readings in cards beside it.
     * Without readings (no adapter) the ring stands alone in the middle.
     */
    private fun paintDrive(g: Graphics2D, s: ClusterState, text: ClusterText, body: Rectangle, p: Palette) {
        val cards = text.cards
        val gap = (4 * unit).roundToInt()
        val ringBox = if (cards.isEmpty()) body else Rectangle(body.x, body.y, (body.width * 0.46).roundToInt(), body.height)
        speedRingFace(g, ringBox, s, text, p)

        if (cards.isEmpty()) return
        val grid = Rectangle(ringBox.x + ringBox.width + gap, body.y, body.width - ringBox.width - gap, body.height)
        val rows = (cards.size + 1) / 2
        val cardH = (grid.height - gap * (rows - 1)) / rows
        cards.forEachIndexed { i, card ->
            // An odd last card takes the whole row.
            val alone = i == cards.size - 1 && cards.size % 2 == 1
            val cardW = if (alone) grid.width else (grid.width - gap) / 2
            val x = grid.x + if (alone) 0 else (i % 2) * (cardW + gap)
            readingCard(g, card, Rectangle(x, grid.y + (i / 2) * (cardH + gap), cardW, cardH), s, p)
        }
    }

    /** The speed in its ring, as big as [box] allows. */
    private fun speedRingFace(g: Graphics2D, box: Rectangle, s: ClusterState, text: ClusterText, p: Palette) {
        val radius = min(box.width, box.height) / 2f
        val cx = box.centerX.toFloat()
        val cy = box.centerY.toFloat() + radius * 0.06f
        val shown = text.speedShown
        val full = if (s.imperial) 130f else 200f
        speedRing(g, cx, cy, radius, (shown ?: 0) / full, p)

        g.color = if (shown == null) p.muted else p.fg
        // Sized for three digits, so the figure keeps its size from 99 to 100.
        g.font = fitFont(g, radius * 0.8f / unit, "888", (radius * 1.25f).roundToInt())
        drawCentered(g, text.speed, cx.roundToInt(), (cy + g.fontMetrics.ascent * 0.36f).roundToInt())
        g.color = p.muted
        g.font = font(Font.BOLD, 5.5f)
        drawCentered(g, text.speedUnit, cx.roundToInt(), (cy + radius * 0.78f).roundToInt())
    }

    // --- The board: one face per slot, each drawn from the readings in the design's own manner -------

    /** Slot [face] of the board in [box]; a face this display doesn't know is an empty card. */
    private fun paintFace(g: Graphics2D, face: String, box: Rectangle, s: ClusterState, text: ClusterText, p: Palette, design: Design, clock: String) {
        val pad = (3 * unit).roundToInt()
        val inner = Rectangle(box.x + pad, box.y + pad, box.width - 2 * pad, box.height - 2 * pad)
        when (face) {
            ClusterFace.SPEED -> speedFace(g, inner, s, text, p, design)
            ClusterFace.CLOCK -> {
                card(g, box, p)
                val date = s.date
                g.color = p.fg
                if (date == null) {
                    g.font = fitFont(g, min(30f, inner.height / unit * 0.6f), clock, inner.width)
                    drawCentered(g, clock, inner.centerX.roundToInt(), (inner.centerY + g.fontMetrics.ascent * 0.36f).roundToInt())
                } else {
                    // The time a little higher, the date under it in the muted tone, as the clock widget shows it.
                    g.font = fitFont(g, min(28f, inner.height / unit * 0.5f), clock, inner.width)
                    val timeAscent = g.fontMetrics.ascent
                    val dateFont = fitFont(g, min(6f, inner.height / unit * 0.12f), date, inner.width)
                    val gap = (2 * unit).roundToInt()
                    val total = timeAscent + gap + g.getFontMetrics(dateFont).ascent
                    val top = inner.centerY.roundToInt() - total / 2
                    drawCentered(g, clock, inner.centerX.roundToInt(), top + timeAscent)
                    g.color = p.muted
                    g.font = dateFont
                    drawCentered(g, date, inner.centerX.roundToInt(), top + timeAscent + gap + g.fontMetrics.ascent)
                }
            }
            ClusterFace.FUEL -> readingCard(g, text.card(ClusterText.Kind.FUEL), box, s, p)
            ClusterFace.RANGE -> readingCard(g, text.card(ClusterText.Kind.RANGE), box, s, p)
            ClusterFace.RPM -> readingCard(g, text.card(ClusterText.Kind.RPM), box, s, p)
            ClusterFace.COOLANT -> readingCard(g, text.card(ClusterText.Kind.COOLANT), box, s, p)
            ClusterFace.TELEMETRY -> telemetryFace(g, box, inner, s, text, p)
            ClusterFace.MEDIA -> {
                card(g, box, p)
                paintMedia(g, s, text, Rectangle(inner.x + pad, inner.y, inner.width - 2 * pad, inner.height), p)
            }
            ClusterFace.NAV -> paintNav(g, s, text, inner, p)
            ClusterFace.MEASURES -> paintMeasures(g, s, text, inner, p)
            ClusterFace.DOORS -> doorsFace(g, box, inner, text, p)
            ClusterFace.MAP -> mapFace(g, box, s, text, p)
            else -> card(g, box, p)
        }
    }

    /** The speed as each design draws it: a ring, a dial, one big number or amber digits. */
    private fun speedFace(g: Graphics2D, box: Rectangle, s: ClusterState, text: ClusterText, p: Palette, design: Design) {
        val shown = text.speedShown
        when (design) {
            Design.CARDS -> speedRingFace(g, box, s, text, p)
            Design.DIALS -> {
                val r = min(box.width, box.height) / 2f
                val cx = box.centerX.toFloat()
                val cy = box.centerY.toFloat() + r * 0.04f
                dial(g, cx, cy, r, shown?.toFloat(), if (s.imperial) 140f else 220f, 20f, 10f, null, p)
                dialReadout(g, cx, cy, r, text.speed, text.speedUnit, shown != null, p)
            }
            Design.LARGE -> {
                val cx = box.centerX.roundToInt()
                val unitBase = box.y + box.height - (2 * unit).roundToInt()
                val numberBase = unitBase - (8 * unit).roundToInt()
                g.color = if (shown == null) p.muted else p.fg
                g.font = fitFont(g, min(52f, (numberBase - box.y) / unit / 0.74f), "888", box.width)
                drawCentered(g, text.speed, cx, numberBase)
                g.color = p.muted
                g.font = font(Font.BOLD, 6f)
                drawCentered(g, text.speedUnit, cx, unitBase)
            }
            Design.RETRO -> {
                g.font = fitFont(g, min(46f, box.height / unit * 0.9f), "888", (box.width * 0.7f).roundToInt())
                val digitsW = g.fontMetrics.stringWidth("888")
                val unitFont = font(Font.BOLD, 7f)
                val unitGap = (3 * unit).roundToInt()
                val x = box.x + (box.width - digitsW - g.getFontMetrics(unitFont).stringWidth(text.speedUnit) - unitGap) / 2
                val base = box.y + box.height / 2 + g.fontMetrics.ascent * 2 / 5
                g.color = p.ghost
                g.drawString("888", x, base)
                g.color = if (shown == null) p.muted else p.fg
                g.drawString(text.speed.takeLast(3).padStart(3, ' '), x, base)
                g.color = p.muted
                g.font = unitFont
                g.drawString(text.speedUnit, x + digitsW + unitGap, base)
            }
        }
    }

    /**
     * Two dials, the speed then the revs with its red zone, as the head
     * unit's Twin dials face draws them; side by side in a wide slot, one
     * over the other in a tall one. "--" without an adapter.
     */
    private fun telemetryFace(g: Graphics2D, box: Rectangle, inner: Rectangle, s: ClusterState, text: ClusterText, p: Palette) {
        card(g, box, p)
        val beside = inner.width > inner.height * 1.2f
        val gap = (2 * unit).roundToInt()
        val r = if (beside) min((inner.width - gap) / 4f, inner.height / 2f) else min(inner.width / 2f, (inner.height - gap) / 4f)
        val (cx1, cy1, cx2, cy2) = if (beside) {
            val cy = inner.centerY.toFloat() + r * 0.04f
            listOf(inner.x + inner.width / 4f, cy, inner.x + inner.width * 3 / 4f, cy)
        } else {
            val cx = inner.centerX.toFloat()
            listOf(cx, inner.y + inner.height / 4f + r * 0.04f, cx, inner.y + inner.height * 3 / 4f + r * 0.04f)
        }
        val speedMax = if (s.imperial) 140f else 220f
        dial(g, cx1, cy1, r, text.speedShown?.toFloat(), speedMax, if (s.imperial) 20f else 20f, 10f, null, p)
        dialReadout(g, cx1, cy1, r, text.speed, text.speedUnit, text.speedShown != null, p)
        dial(g, cx2, cy2, r, s.rpm?.let { it / 1000f }, 7f, 1f, 0.5f, 5f, p)
        dialReadout(g, cx2, cy2, r, s.rpm?.toString() ?: "--", text.rpmLabel, s.rpm != null, p)
    }

    /** How much smaller than the page's own body [box] is, for the type drawn in it: never under a bit more than half. */
    private fun scaleFor(box: Rectangle): Float {
        val pageWidth = width - 2 * inset - (10 * unit)
        return (box.width / pageWidth).coerceIn(0.55f, 1f)
    }

    /**
     * The display's own map: seen from behind the car with the buildings
     * standing ([ChaseMap]), or flat with north up as the head unit chooses
     * ([ClusterState.mapView]); raster tiles from [tiles], the route in the
     * accent, the next turn in a card over it.
     * Without a fix it says so; without tiles the route and the car are drawn
     * on a plain ground, so the slot still tells where the car is going.
     */
    private fun mapFace(g: Graphics2D, box: Rectangle, s: ClusterState, text: ClusterText, p: Palette) {
        val pos = s.position
        val look = s.mapView ?: ClusterState.MapView()
        // The tilted map covers its whole card: no card under it.
        if (pos == null || !look.tilted) card(g, box, p)
        if (pos == null) {
            emptyState(g, box, text.noPosition, p) { cx, cy, size -> navArrow(g, cx, cy, size, p.muted) }
            return
        }
        val source = if (s.night) nightTiles ?: tiles else tiles
        val zoom = source?.zoom ?: DisplayConfig.DEFAULT_MAP_ZOOM
        val k = scaleFor(box)
        val radius = (6 * unit).roundToInt()
        val oldClip = g.clip
        g.clip = RoundRectangle2D.Float(box.x.toFloat(), box.y.toFloat(), box.width.toFloat(), box.height.toFloat(), radius.toFloat(), radius.toFloat())
        try {
            if (look.tilted) {
                chase.paint(
                    g, box, s, pos, look, route, source, s.night && nightTiles == null, buildings.takeIf { look.buildings },
                    p.accent, p.night, max(1, zoom - 4), min(MAX_CHASE_ZOOM, zoom + 1), System.nanoTime()
                )
                chaseMoving = chase.moving
                overlaysFrom = System.nanoTime()
            } else {
                chaseMoving = false
                flatMap(g, box, pos, source, zoom, s, p)
            }

            val pad = (2.5f * unit).roundToInt()
            // North, top right, turning with the map when it turns with the car.
            compass(g, box, pad, if (look.tilted) chase.headingDeg else 0.0, p)
            // A scale bar, bottom right, on the flat map only: in perspective a metre has no one length.
            val metres = 100
            val barW = (metres / WebMercator.metersPerPixel(pos.lat, zoom)).roundToInt().coerceIn(20, box.width / 3)
            if (!look.tilted) {
                val barY = box.y + box.height - pad - (if (s.nav?.eta.isNullOrBlank()) 0 else (9 * unit * k).roundToInt())
                g.color = p.muted
                g.fillRect(box.x + box.width - pad - barW, barY - 2, barW, 3)
                g.font = font(Font.PLAIN, 3f)
                drawRight(g, "$metres m", box.x + box.width - pad, barY - (1.2f * unit).roundToInt())
            }

            // The words over the map, as they are on the flat one; the tilted one copies them from a sheet, after the clip.
            if (!look.tilted) mapWords(g, box, s, text, p, k, pad, barW, source == null)
        } finally {
            g.clip = oldClip
        }
        if (look.tilted) {
            val pad = (2.5f * unit).roundToInt()
            val barW = (100 / WebMercator.metersPerPixel(pos.lat, zoom)).roundToInt().coerceIn(20, box.width / 3)
            // The turn card and the arrival strip change with the directions, not with every picture:
            // drawn once on a clear sheet, then only their patches are copied over the map.
            val key = listOf(s.nav, s.maneuver, s.labels, s.night, s.accent, s.design, box.width, box.height, source == null)
            val sheet = wordsSheet?.takeIf { it.width == box.width && it.height == box.height }
                ?: BufferedImage(box.width, box.height, BufferedImage.TYPE_INT_ARGB).also { wordsSheet = it; wordsKey = null }
            if (key != wordsKey) {
                val w = sheet.createGraphics()
                try {
                    w.composite = java.awt.AlphaComposite.Clear
                    w.fillRect(0, 0, box.width, box.height)
                    w.composite = java.awt.AlphaComposite.SrcOver
                    w.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    w.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                    w.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
                    w.stroke = BasicStroke(max(2f, unit / 2))
                    w.translate(-box.x, -box.y)
                    wordsAt = mapWords(w, box, s, text, p, k, pad, barW, source == null).map { Rectangle(it.x - box.x, it.y - box.y, it.width, it.height) }
                } finally {
                    w.dispose()
                }
                wordsKey = key
            }
            for (r in wordsAt) {
                val x = box.x + r.x
                val y = box.y + r.y
                g.drawImage(sheet, x, y, x + r.width, y + r.height, r.x, r.y, r.x + r.width, r.y + r.height, null)
            }
            if (overlaysFrom != 0L) chase.overlaysTook(System.nanoTime() - overlaysFrom)
            overlaysFrom = 0L
        }
    }

    private var wordsSheet: BufferedImage? = null
    private var wordsKey: Any? = null
    private var wordsAt: List<Rectangle> = emptyList()

    /**
     * What is written over the map: the next turn in a card, the arrival
     * along the bottom, and what is missing when there are no tiles. Returns
     * where each went, a little wider than drawn (for the tilted map's sheet).
     */
    private fun mapWords(g: Graphics2D, box: Rectangle, s: ClusterState, text: ClusterText, p: Palette, k: Float, pad: Int, barW: Int, noTiles: Boolean): List<Rectangle> {
        val drawn = ArrayList<Rectangle>()
        val margin = 2
        fun drew(r: Rectangle) { drawn += Rectangle(r.x - margin, r.y - margin, r.width + 2 * margin, r.height + 2 * margin) }

        // No tiles to draw: say what is missing, small, along the bottom.
        if (noTiles) {
            g.color = p.muted
            g.font = font(Font.PLAIN, 3.2f)
            val base = box.y + box.height - pad - (0.5f * unit).roundToInt()
            val room = box.width - 2 * pad - barW - pad
            drawClipped(g, text.noTiles, box.x + pad, base, room)
            drew(Rectangle(box.x + pad, base - g.fontMetrics.ascent, room, g.fontMetrics.ascent + g.fontMetrics.descent))
        }

        // The next turn in a card over the map, and the arrival along the bottom.
        s.nav?.let { nav ->
            val signSide = (11 * unit * k).roundToInt()
            val cardPad = (2 * unit * k).roundToInt()
            val distFont = fitFont(g, 7f * k, nav.distance.ifBlank { "0 m" }, (box.width * 0.5f).roundToInt())
            val lineFont = font(Font.PLAIN, 3.8f * k)
            val room = (box.width * 0.6f).roundToInt() - signSide - 3 * cardPad
            val wordsW = max(g.getFontMetrics(distFont).stringWidth(nav.distance), min(room, g.getFontMetrics(lineFont).stringWidth(nav.instruction)))
            // The distance over the instruction; the card as tall as the two lines or the sign need.
            val distAscent = g.getFontMetrics(distFont).ascent
            val lineAscent = g.getFontMetrics(lineFont).ascent
            val gapY = (1.2f * unit * k).roundToInt()
            val textH = distAscent + gapY + lineAscent
            val cardH = max(signSide, textH) + 2 * cardPad
            val cardW = signSide + 3 * cardPad + wordsW
            val cardBox = Rectangle(box.x + pad, box.y + pad, cardW, cardH)
            card(g, cardBox, p)
            drew(cardBox)
            val signY = cardBox.y + (cardH - signSide) / 2
            g.color = p.accent
            g.fillRoundRect(cardBox.x + cardPad, signY, signSide, signSide, (3 * unit).roundToInt(), (3 * unit).roundToInt())
            maneuver(
                g, s.maneuver?.let(Maneuver::named) ?: Maneuver.of(nav.instruction),
                cardBox.x + cardPad + signSide / 2f, signY + signSide / 2f, signSide * 0.6f, onAccent(p.accent), p.accent
            )
            val tx = cardBox.x + 2 * cardPad + signSide
            val distBase = cardBox.y + (cardH - textH) / 2 + distAscent
            g.color = p.fg
            g.font = distFont
            if (nav.distance.isNotBlank()) g.drawString(nav.distance, tx, distBase)
            g.color = p.muted
            g.font = lineFont
            drawClipped(g, nav.instruction, tx, distBase + gapY + lineAscent, wordsW)
            if (nav.eta.isNotBlank()) {
                val stripH = (8 * unit * k).roundToInt()
                val strip = Rectangle(box.x + pad, box.y + box.height - pad - stripH, box.width - 2 * pad, stripH)
                card(g, strip, p)
                drew(strip)
                val (label, value) = text.arriveParts(nav.eta)
                val mid = strip.y + strip.height / 2
                val small = font(Font.BOLD, 3.6f * k)
                val big = font(Font.BOLD, 5.5f * k)
                g.color = p.muted
                g.font = small
                g.drawString(label.uppercase(), strip.x + cardPad * 2, mid + g.fontMetrics.ascent * 2 / 5)
                val labelW = if (label.isEmpty()) 0 else g.fontMetrics.stringWidth(label.uppercase()) + (1.5f * unit).roundToInt()
                g.color = p.fg
                g.font = big
                g.drawString(value, strip.x + cardPad * 2 + labelW, mid + g.fontMetrics.ascent * 2 / 5)
            }
        }
        return drawn
    }

    private var overlaysFrom = 0L

    /** The map flat, north up, the car in the middle: as the display drew it before the tilted view. */
    private fun flatMap(g: Graphics2D, box: Rectangle, pos: ClusterState.Position, source: TileSource?, zoom: Int, s: ClusterState, p: Palette) {
        // World pixels to the screen: the car's world position lands on the box's centre.
        val ox = box.centerX - WebMercator.x(pos.lon, zoom)
        val oy = box.centerY - WebMercator.y(pos.lat, zoom)
        fun sx(lon: Double) = (WebMercator.x(lon, zoom) + ox).toFloat()
        fun sy(lat: Double) = (WebMercator.y(lat, zoom) + oy).toFloat()

        if (source != null) {
            val t = WebMercator.TILE
            val x0 = WebMercator.tileOf(box.x - ox)
            val x1 = WebMercator.tileOf(box.x + box.width - ox)
            val y0 = WebMercator.tileOf(box.y - oy)
            val y1 = WebMercator.tileOf(box.y + box.height - oy)
            val ring = ArrayList<TileKey>()
            for (ty in y0 - 1..y1 + 1) for (tx in x0 - 1..x1 + 1) {
                val onScreen = tx in x0..x1 && ty in y0..y1
                val image = if (onScreen) source.tile(zoom, tx, ty) else null
                if (!onScreen) ring += TileKey(zoom, tx, ty)
                if (!onScreen) continue
                val x = (tx * t + ox).roundToInt()
                val y = (ty * t + oy).roundToInt()
                if (image != null) {
                    g.drawImage(image, x, y, t, t, null)
                } else {
                    g.color = p.track
                    g.fillRect(x, y, t, t)
                }
            }
            // By night on the day's tiles: dimmed, as a dashboard dims.
            if (s.night && nightTiles == null) {
                g.color = Color(0, 0, 0, 120)
                g.fillRect(box.x, box.y, box.width, box.height)
            }
            source.prefetch(ring + routeAhead(pos, zoom))
        } else {
            g.color = p.card
            g.fillRect(box.x, box.y, box.width, box.height)
            // A faint grid of 100 m, so movement can be seen without tiles.
            val step = (100.0 / WebMercator.metersPerPixel(pos.lat, zoom)).toFloat().coerceAtLeast(12f)
            g.color = p.track
            g.stroke = BasicStroke(1f)
            var gx = (box.centerX % step).toFloat()
            while (gx < box.width) { g.draw(Line2D.Float(box.x + gx, box.y.toFloat(), box.x + gx, (box.y + box.height).toFloat())); gx += step }
            var gy = (box.centerY % step).toFloat()
            while (gy < box.height) { g.draw(Line2D.Float(box.x.toFloat(), box.y + gy, (box.x + box.width).toFloat(), box.y + gy)); gy += step }
        }

        // The route: a dark casing, the accent on it.
        val pts = route
        if (pts.size >= 4) {
            val path = Path2D.Float()
            for (i in 0 until pts.size / 2) {
                val x = sx(pts[2 * i + 1])
                val y = sy(pts[2 * i])
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            g.stroke = BasicStroke(2.4f * unit, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.color = mix(if (s.night) Color.BLACK else Color.WHITE, p.accent, 0.35f)
            g.draw(path)
            g.stroke = BasicStroke(1.5f * unit, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.color = p.accent
            g.draw(path)
        }

        // The car: a chevron turned to its heading, in a soft halo; a dot when it stands still.
        val cx = box.centerX.toFloat()
        val cy = box.centerY.toFloat()
        val size = 4.5f * unit
        g.color = Color(p.accent.red, p.accent.green, p.accent.blue, 70)
        g.fill(Ellipse2D.Float(cx - size, cy - size, 2 * size, 2 * size))
        val heading = pos.headingDeg
        if (heading == null) {
            g.color = p.accent
            g.fill(Ellipse2D.Float(cx - size * 0.45f, cy - size * 0.45f, size * 0.9f, size * 0.9f))
            g.color = p.fg
            g.stroke = BasicStroke(max(2f, unit * 0.4f))
            g.draw(Ellipse2D.Float(cx - size * 0.45f, cy - size * 0.45f, size * 0.9f, size * 0.9f))
        } else {
            val saved = g.transform
            g.rotate(Math.toRadians(heading.toDouble()), cx.toDouble(), cy.toDouble())
            val chevron = Path2D.Float().apply {
                moveTo(cx, cy - size)
                lineTo(cx + size * 0.72f, cy + size * 0.65f)
                lineTo(cx, cy + size * 0.25f)
                lineTo(cx - size * 0.72f, cy + size * 0.65f)
                closePath()
            }
            g.color = p.accent
            g.fill(chevron)
            g.color = p.fg
            g.stroke = BasicStroke(max(2f, unit * 0.4f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.draw(chevron)
            g.transform = saved
        }
    }

    /**
     * The tiles along the route ahead of the car, up to [ROUTE_AHEAD_M], so they
     * are on the card before the car gets there: from the route point nearest
     * the car onward, one tile per point, without repeats.
     */
    private fun routeAhead(pos: ClusterState.Position, zoom: Int): List<TileKey> {
        val pts = route
        if (pts.size < 4) return emptyList()
        val px = WebMercator.x(pos.lon, zoom)
        val py = WebMercator.y(pos.lat, zoom)
        var nearest = 0
        var best = Double.MAX_VALUE
        for (i in 0 until pts.size / 2) {
            val dx = WebMercator.x(pts[2 * i + 1], zoom) - px
            val dy = WebMercator.y(pts[2 * i], zoom) - py
            val d = dx * dx + dy * dy
            if (d < best) { best = d; nearest = i }
        }
        val perPx = WebMercator.metersPerPixel(pos.lat, zoom)
        val keys = LinkedHashSet<TileKey>()
        var metres = 0.0
        var lastX = WebMercator.x(pts[2 * nearest + 1], zoom)
        var lastY = WebMercator.y(pts[2 * nearest], zoom)
        for (i in nearest until pts.size / 2) {
            val x = WebMercator.x(pts[2 * i + 1], zoom)
            val y = WebMercator.y(pts[2 * i], zoom)
            metres += Math.hypot(x - lastX, y - lastY) * perPx
            lastX = x
            lastY = y
            if (metres > ROUTE_AHEAD_M || keys.size >= ROUTE_AHEAD_TILES) break
            keys += TileKey(zoom, WebMercator.tileOf(x), WebMercator.tileOf(y))
        }
        return keys.toList()
    }

    /** The doors: what is open, in the warning colour, else a tick. */
    private fun doorsFace(g: Graphics2D, box: Rectangle, inner: Rectangle, text: ClusterText, p: Palette) {
        card(g, box, p)
        val unknown = text.doorsUnknown
        if (unknown != null) {
            // No car box data yet: said, not shown as "all closed".
            g.color = p.muted
            g.font = font(Font.PLAIN, 5f)
            val lines = wrap(g, unknown, inner.width, max(1, inner.height / g.fontMetrics.height))
            var y = inner.centerY.roundToInt() - (lines.size - 1) * g.fontMetrics.height / 2 + g.fontMetrics.ascent * 2 / 5
            for (line in lines) {
                drawCentered(g, line, inner.centerX.roundToInt(), y)
                y += g.fontMetrics.height
            }
            return
        }
        val open = text.open
        if (open == null) {
            val r = min(inner.width, inner.height) * 0.22f
            val cx = inner.centerX.toFloat()
            val cy = inner.centerY.toFloat()
            g.color = p.accent
            g.stroke = BasicStroke(r * 0.28f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.draw(Path2D.Float().apply {
                moveTo(cx - r * 0.8f, cy)
                lineTo(cx - r * 0.2f, cy + r * 0.6f)
                lineTo(cx + r * 0.9f, cy - r * 0.6f)
            })
            return
        }
        g.color = p.warn
        g.font = font(Font.BOLD, 6.5f)
        val lines = wrap(g, open, inner.width, max(1, inner.height / g.fontMetrics.height))
        var y = inner.centerY.roundToInt() - (lines.size - 1) * g.fontMetrics.height / 2 + g.fontMetrics.ascent * 2 / 5
        for (line in lines) {
            drawCentered(g, line, inner.centerX.roundToInt(), y)
            y += g.fontMetrics.height
        }
    }

    /** A 270° ring open at the bottom, ticked, filled in the accent up to [fraction]. */
    private fun speedRing(g: Graphics2D, cx: Float, cy: Float, r: Float, fraction: Float, p: Palette) {
        val w = r * 0.1f
        val arc = Arc2D.Float(cx - r + w / 2, cy - r + w / 2, 2 * r - w, 2 * r - w, 225f, -270f, Arc2D.OPEN)
        val round = BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.stroke = round
        g.color = p.track
        g.draw(arc)
        // Ticks inside the ring, a longer one every fifth.
        val outer = r - w * 1.7f
        for (t in 0..20) {
            val major = t % 5 == 0
            val a = Math.toRadians(225.0 - 270.0 * t / 20)
            val inner = outer - if (major) w * 0.9f else w * 0.45f
            g.stroke = BasicStroke(max(2f, w * if (major) 0.18f else 0.1f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.color = if (major) p.muted else p.track
            g.draw(Line2D.Float(cx + outer * cos(a).toFloat(), cy - outer * sin(a).toFloat(), cx + inner * cos(a).toFloat(), cy - inner * sin(a).toFloat()))
        }
        val f = fraction.coerceIn(0f, 1f)
        if (f <= 0f) return
        g.stroke = round
        g.color = p.accent
        arc.extent = -270f * f
        g.draw(arc)
    }

    private fun readingCard(g: Graphics2D, card: ClusterText.Reading, box: Rectangle, s: ClusterState, p: Palette) {
        card(g, box, p)
        val pad = (4 * unit).roundToInt()
        val inner = box.width - 2 * pad
        g.color = p.muted
        g.font = font(Font.BOLD, 4.2f)
        drawClipped(g, card.label.uppercase(), box.x + pad, box.y + pad + g.fontMetrics.ascent, inner)
        val tone = toneOf(card, s, p)
        g.color = tone ?: p.fg
        g.font = fitFont(g, min(13f, box.height / unit * 0.34f), card.value, inner)
        // Every figure on the same line, the fuel's gauge under it.
        g.drawString(card.value, box.x + pad, box.y + box.height - pad - (5 * unit).roundToInt())
        if (card.kind == ClusterText.Kind.FUEL) {
            val h = max(4, (1.6f * unit).roundToInt())
            bar(g, box.x + pad, box.y + box.height - pad - h, inner, (s.fuelPct ?: 0) / 100f, tone ?: p.accent, p.track)
        }
    }

    /** Two round gauges as in the car: speed, and revs when the car gives them; the rest in a line under them. */
    private fun paintDials(g: Graphics2D, s: ClusterState, text: ClusterText, body: Rectangle, p: Palette) {
        val rest = text.cards.filter { it.kind != ClusterText.Kind.RPM }
        val rowH = if (rest.isEmpty()) 0 else (12 * unit).roundToInt()
        val gap = (6 * unit).roundToInt()
        val area = Rectangle(body.x, body.y, body.width, body.height - rowH)
        val rpm = s.rpm
        val r = if (rpm != null) min((area.width - gap) / 4f, area.height / 2f) else min(area.width, area.height) / 2f
        val cy = area.centerY.toFloat() + r * 0.04f
        val speedCx = (if (rpm != null) area.centerX - gap / 2.0 - r else area.centerX).toFloat()
        val shown = text.speedShown
        val full = if (s.imperial) 140f else 220f
        dial(g, speedCx, cy, r, shown?.toFloat(), full, 20f, 10f, null, p)
        dialReadout(g, speedCx, cy, r, text.speed, text.speedUnit, shown != null, p)
        if (rpm != null) {
            val rpmCx = (area.centerX + gap / 2.0 + r).toFloat()
            dial(g, rpmCx, cy, r, rpm / 1000f, 7f, 1f, 0.5f, 5f, p)
            dialReadout(g, rpmCx, cy, r, "$rpm", text.rpmLabel, true, p)
        }
        if (rest.isEmpty()) return
        // The other readings in a line along the bottom, each its caption then its figure.
        val y = body.y + body.height - rowH / 2
        val slot = body.width / rest.size
        val small = font(Font.BOLD, 4.2f)
        val big = font(Font.BOLD, 7f)
        val space = (2 * unit).roundToInt()
        rest.forEachIndexed { i, card ->
            val label = card.label.uppercase()
            val tone = toneOf(card, s, p)
            val lw = g.getFontMetrics(small).stringWidth(label)
            val vw = g.getFontMetrics(big).stringWidth(card.value)
            val barW = if (card.kind == ClusterText.Kind.FUEL) (14 * unit).roundToInt() else 0
            val total = lw + space + vw + if (barW > 0) space + barW else 0
            var x = body.x + slot * i + (slot - total) / 2
            g.color = p.muted
            g.font = small
            g.drawString(label, x, y + g.fontMetrics.ascent * 2 / 5)
            x += lw + space
            g.color = tone ?: p.fg
            g.font = big
            g.drawString(card.value, x, y + g.fontMetrics.ascent * 2 / 5)
            if (barW > 0) bar(g, x + vw + space, y - (0.8f * unit).roundToInt(), barW, (s.fuelPct ?: 0) / 100f, tone ?: p.accent, p.track)
        }
    }

    /**
     * A round gauge from 0 to [max] over 270 degrees, open at the bottom: a numbered tick every [major],
     * a short one every [minor], red from [redFrom], and a needle at [value] (none when null).
     */
    private fun dial(
        g: Graphics2D, cx: Float, cy: Float, r: Float, value: Float?, max: Float, major: Float, minor: Float, redFrom: Float?, p: Palette
    ) {
        fun angle(v: Float) = Math.toRadians(225.0 - 270.0 * (v / max).coerceIn(0f, 1f))
        fun at(v: Float, radius: Float) = angle(v).let { a -> (cx + radius * cos(a).toFloat()) to (cy - radius * sin(a).toFloat()) }
        val w = r * 0.035f
        val ring = r - w
        fun arc(from: Float, to: Float) =
            Arc2D.Float(cx - ring, cy - ring, 2 * ring, 2 * ring, 225f - 270f * from / max, -270f * (to - from) / max, Arc2D.OPEN)
        g.stroke = BasicStroke(w, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND)
        g.color = p.track
        g.draw(arc(0f, max))
        val thick = BasicStroke(w * 2.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND)
        if (redFrom != null) {
            g.color = p.critical
            g.stroke = thick
            g.draw(arc(redFrom, max))
        }
        if (value != null && value > 0f) {
            g.color = p.accent
            g.stroke = thick
            g.draw(arc(0f, value.coerceAtMost(max)))
        }
        g.font = font(Font.BOLD, r / unit * 0.11f)
        val steps = (max / minor).roundToInt()
        val perMajor = (major / minor).roundToInt()
        for (i in 0..steps) {
            val v = i * minor
            val isMajor = i % perMajor == 0
            val red = redFrom != null && v >= redFrom
            val (x1, y1) = at(v, ring - w * 1.5f)
            val (x2, y2) = at(v, ring - w * if (isMajor) 4.5f else 3f)
            g.color = if (red) p.critical else if (isMajor) p.fg else p.muted
            g.stroke = BasicStroke(max(2f, w * if (isMajor) 0.9f else 0.5f), BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND)
            g.draw(Line2D.Float(x1, y1, x2, y2))
            if (isMajor) {
                val (lx, ly) = at(v, ring - w * 8f)
                g.color = if (red) p.critical else p.muted
                drawCentered(g, v.roundToInt().toString(), lx.roundToInt(), (ly + g.fontMetrics.ascent * 0.38f).roundToInt())
            }
        }
        if (value == null) return
        // The needle, tapered, from just behind the hub.
        val a = angle(value)
        val tip = ring - w * 2f
        val tail = r * 0.12f
        val half = r * 0.025f
        val dx = cos(a).toFloat()
        val dy = -sin(a).toFloat()
        g.color = p.needle
        g.fill(Path2D.Float().apply {
            moveTo(cx + dx * tip, cy + dy * tip)
            lineTo(cx - dx * tail - dy * half, cy - dy * tail + dx * half)
            lineTo(cx - dx * tail + dy * half, cy - dy * tail - dx * half)
            closePath()
        })
        val hub = r * 0.07f
        g.color = p.fg
        g.fill(Ellipse2D.Float(cx - hub, cy - hub, 2 * hub, 2 * hub))
    }

    /** The gauge's figure under its hub, its unit under that. */
    private fun dialReadout(g: Graphics2D, cx: Float, cy: Float, r: Float, value: String, caption: String, live: Boolean, p: Palette) {
        g.color = if (live) p.fg else p.muted
        g.font = font(Font.BOLD, r / unit * 0.2f)
        drawCentered(g, value, cx.roundToInt(), (cy + r * 0.52f).roundToInt())
        g.color = p.muted
        g.font = font(Font.BOLD, r / unit * 0.08f)
        drawCentered(g, caption, cx.roundToInt(), (cy + r * 0.72f).roundToInt())
    }

    /** One number as big as the screen allows, the readings in a ruled line under it. */
    private fun paintLarge(g: Graphics2D, s: ClusterState, text: ClusterText, body: Rectangle, p: Palette) {
        val cards = text.cards
        val rowH = if (cards.isEmpty()) 0 else (18 * unit).roundToInt()
        val cx = body.centerX.roundToInt()
        // The unit just above the line, the number as tall as the room over it allows.
        val unitBase = body.y + body.height - rowH - (4 * unit).roundToInt()
        val numberBase = unitBase - (9 * unit).roundToInt()
        g.color = if (text.speedShown == null) p.muted else p.fg
        g.font = fitFont(g, min(52f, (numberBase - body.y) / unit / 0.74f), "888", body.width)
        drawCentered(g, text.speed, cx, numberBase)
        g.color = p.muted
        g.font = font(Font.BOLD, 6f)
        drawCentered(g, text.speedUnit, cx, unitBase)
        if (cards.isEmpty()) return
        val top = body.y + body.height - rowH
        g.color = p.track
        g.stroke = BasicStroke(max(2f, unit / 3))
        g.draw(Line2D.Float(body.x.toFloat(), top.toFloat(), (body.x + body.width).toFloat(), top.toFloat()))
        val slot = body.width / cards.size
        cards.forEachIndexed { i, card ->
            val x = body.x + slot * i + slot / 2
            if (i > 0) {
                g.color = p.track
                g.draw(Line2D.Float((body.x + slot * i).toFloat(), top + 4 * unit, (body.x + slot * i).toFloat(), top + rowH - 2 * unit))
            }
            g.color = toneOf(card, s, p) ?: p.fg
            g.font = font(Font.BOLD, 9f)
            drawCentered(g, card.value, x, top + (11 * unit).roundToInt())
            g.color = p.muted
            g.font = font(Font.BOLD, 4f)
            drawCentered(g, card.label.uppercase(), x, top + (17 * unit).roundToInt())
        }
    }

    /**
     * An old digital dash: the revs as a row of segments, the speed in big digits over
     * their unlit "888", the readings as segment gauges and figures under it.
     */
    private fun paintRetro(g: Graphics2D, s: ClusterState, text: ClusterText, body: Rectangle, p: Palette) {
        val segH = (6 * unit).roundToInt()
        var top = body.y
        s.rpm?.let { rpm ->
            g.color = p.muted
            g.font = font(Font.BOLD, 4.2f)
            g.drawString(text.rpmLabel.uppercase(), body.x, top + g.fontMetrics.ascent)
            drawRight(g, "$rpm", body.x + body.width, top + g.fontMetrics.ascent)
            top += (6 * unit).roundToInt()
            segments(g, body.x, top, body.width, segH, 40, rpm / RETRO_RPM_FULL, 0.8f, p)
            top += segH + (2 * unit).roundToInt()
        }
        val rest = text.cards.filter { it.kind != ClusterText.Kind.RPM }
        val rowH = if (rest.isEmpty()) 0 else (16 * unit).roundToInt()
        // The digits right-aligned over their unlit 888, like a real display.
        val area = Rectangle(body.x, top, body.width, body.y + body.height - rowH - top)
        g.font = fitFont(g, min(46f, area.height / unit * 0.9f), "888", (area.width * 0.7f).roundToInt())
        val digitsW = g.fontMetrics.stringWidth("888")
        val unitFont = font(Font.BOLD, 7f)
        val unitGap = (3 * unit).roundToInt()
        val x = body.x + (body.width - digitsW - g.getFontMetrics(unitFont).stringWidth(text.speedUnit) - unitGap) / 2
        val base = area.y + area.height / 2 + g.fontMetrics.ascent * 2 / 5
        g.color = p.ghost
        g.drawString("888", x, base)
        g.color = if (text.speedShown == null) p.muted else p.fg
        g.drawString(text.speed.takeLast(3).padStart(3, ' '), x, base)
        g.color = p.muted
        g.font = unitFont
        g.drawString(text.speedUnit, x + digitsW + unitGap, base)
        if (rest.isEmpty()) return
        val rowTop = body.y + body.height - rowH
        val gap = (4 * unit).roundToInt()
        val slot = (body.width - gap * (rest.size - 1)) / rest.size
        rest.forEachIndexed { i, card ->
            val bx = body.x + i * (slot + gap)
            g.color = p.muted
            g.font = font(Font.BOLD, 4.2f)
            drawClipped(g, card.label.uppercase(), bx, rowTop + g.fontMetrics.ascent, slot)
            g.color = toneOf(card, s, p) ?: p.fg
            g.font = font(Font.BOLD, 8f)
            drawRight(g, card.value, bx + slot, rowTop + (8.5f * unit).roundToInt())
            val fraction = when (card.kind) {
                ClusterText.Kind.FUEL -> (s.fuelPct ?: 0) / 100f
                ClusterText.Kind.COOLANT -> ((s.coolantC ?: 0) - 40) / 80f
                ClusterText.Kind.RANGE -> (s.rangeKm ?: 0) / 800f
                ClusterText.Kind.RPM -> 0f
            }
            segments(g, bx, rowTop + (11 * unit).roundToInt(), slot, (3.5f * unit).roundToInt(), 12, fraction, null, p)
        }
    }

    /** [count] blocks across [w], lit up to [fraction]; from [hotFrom] of the way on, lit ones are red. */
    private fun segments(g: Graphics2D, x: Int, y: Int, w: Int, h: Int, count: Int, fraction: Float, hotFrom: Float?, p: Palette) {
        val gap = max(2, w / count / 4)
        val bw = (w - gap * (count - 1)) / count.toFloat()
        val lit = (fraction.coerceIn(0f, 1f) * count).roundToInt()
        for (i in 0 until count) {
            g.color = when {
                i >= lit -> p.ghost
                hotFrom != null && i >= count * hotFrom -> p.critical
                else -> p.fg
            }
            g.fill(Rectangle2D.Float(x + i * (bw + gap), y.toFloat(), bw, h.toFloat()))
        }
    }

    /** A figure out of the ordinary takes the colour of its warning; null for the usual colour. */
    private fun toneOf(card: ClusterText.Reading, s: ClusterState, p: Palette): Color? = when (card.kind) {
        ClusterText.Kind.COOLANT -> s.coolantC?.let { c -> if (c >= HOT_C) p.critical else if (c >= WARM_C) p.warn else if (c < COLD_C) p.cold else null }
        ClusterText.Kind.FUEL -> s.fuelPct?.takeIf { it <= LOW_FUEL_PCT }?.let { p.warn }
        else -> null
    }

    private fun paintMedia(g: Graphics2D, s: ClusterState, text: ClusterText, body: Rectangle, p: Palette) {
        val media = s.media
        if (media == null) {
            emptyState(g, body, text.nothingPlaying, p) { cx, cy, size -> musicNote(g, cx, cy, size, p.muted) }
            return
        }
        // In a board slot narrower than the page, the type shrinks with it.
        val k = scaleFor(body)
        // The cover: the track's own picture when the head unit sent it (ClusterArt), cropped to
        // the square; else a tile in the accent with a note, as before.
        val side = min(body.height - (4 * unit).roundToInt(), (body.width * 0.4).roundToInt())
        val cover = Rectangle(body.x, body.y + (body.height - side) / 2, side, side)
        val radius = (8 * unit).roundToInt()
        val art = coverArt?.takeIf { it.first == media.artKey }?.second
        if (art != null) {
            val oldClip = g.clip
            g.clip(RoundRectangle2D.Float(cover.x.toFloat(), cover.y.toFloat(), side.toFloat(), side.toFloat(), radius.toFloat(), radius.toFloat()))
            // Fill the square, the overflow cropped evenly on both sides.
            val scale = side.toDouble() / min(art.width, art.height)
            val w = (art.width * scale).roundToInt()
            val h = (art.height * scale).roundToInt()
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(art, cover.x - (w - side) / 2, cover.y - (h - side) / 2, w, h, null)
            g.clip = oldClip
        } else {
            g.paint = GradientPaint(cover.x.toFloat(), cover.y.toFloat(), p.accent, (cover.x + side).toFloat(), (cover.y + side).toFloat(), mix(p.accent, Color.BLACK, 0.6f))
            g.fillRoundRect(cover.x, cover.y, side, side, radius, radius)
            musicNote(g, cover.centerX.toFloat(), cover.centerY.toFloat(), side * 0.42f, Color(255, 255, 255, 235))
        }

        val x = cover.x + side + (7 * unit * k).roundToInt()
        val room = body.x + body.width - x
        var y = cover.y + (2 * unit).roundToInt()
        // Playing or paused, and how far along, on the cover's bottom line: the words stay above it.
        val icon = 6 * unit * k
        val iconY = cover.y + side - icon / 2 - unit
        val wordsEnd = (iconY - icon).roundToInt()
        if (media.app.isNotBlank()) {
            g.color = p.accent
            g.font = font(Font.BOLD, 4.2f * k)
            y += g.fontMetrics.ascent
            drawClipped(g, media.app.uppercase(), x, y, room)
            y += (3 * unit * k).roundToInt()
        }
        g.color = p.fg
        g.font = font(Font.BOLD, 10f * k)
        val lineH = g.fontMetrics.ascent + (1.5f * unit * k).roundToInt()
        val titleLines = wrap(g, media.title, room, if (y + 2 * lineH <= wordsEnd) 2 else 1)
        for (line in titleLines) {
            y += g.fontMetrics.ascent
            g.drawString(line, x, y)
            y += (1.5f * unit * k).roundToInt()
        }
        if (media.artist.isNotBlank()) {
            g.color = p.muted
            g.font = font(Font.PLAIN, 6.5f * k)
            val base = y + g.fontMetrics.ascent + (1 * unit * k).roundToInt()
            if (base <= wordsEnd) drawClipped(g, media.artist, x, base, room)
        }

        playState(g, x + icon / 2, iconY, icon, media.playing, p.fg)
        if (media.durationMs > 0) {
            g.font = font(Font.PLAIN, 4.5f * k)
            val times = "${mmss(media.positionMs)} / ${mmss(media.durationMs)}"
            val barX = (x + icon + 4 * unit).roundToInt()
            val barW = body.x + body.width - barX - g.fontMetrics.stringWidth(times) - (4 * unit).roundToInt()
            bar(g, barX, (iconY - 0.8f * unit).roundToInt(), barW, media.positionMs.toFloat() / media.durationMs, p.accent, p.track)
            g.color = p.muted
            drawRight(g, times, body.x + body.width, (iconY + g.fontMetrics.ascent * 0.4f).roundToInt())
        }
    }

    private fun paintNav(g: Graphics2D, s: ClusterState, text: ClusterText, body: Rectangle, p: Palette) {
        val nav = s.nav
        if (nav == null) {
            // A navigation app running with nothing to say is said as such, as the head unit's tile does.
            val line = s.navApp?.let { text.navRunning(it) } ?: text.noRoute
            emptyState(g, body, line, p) { cx, cy, size -> routeSign(g, cx, cy, size, p.muted) }
            return
        }
        // Bottom strip: when we get there, and how fast we're going.
        val stripH = (13 * unit).roundToInt()
        val strip = Rectangle(body.x, body.y + body.height - stripH, body.width, stripH)

        // The next turn as a big sign in the accent, the distance to it beside it.
        val gap = (5 * unit).roundToInt()
        val side = min(strip.y - body.y - gap, (body.width * 0.34).roundToInt())
        val sign = Rectangle(body.x, body.y, side, side)
        val radius = (8 * unit).roundToInt()
        g.color = p.accent
        g.fillRoundRect(sign.x, sign.y, side, side, radius, radius)
        maneuver(g, s.maneuver?.let(Maneuver::named) ?: Maneuver.of(nav.instruction), sign.centerX.toFloat(), sign.centerY.toFloat(), side * 0.6f, onAccent(p.accent), p.accent)

        // In a board slot narrower than the page, the type shrinks with it.
        val k = scaleFor(body)
        val x = sign.x + side + (7 * unit * k).roundToInt()
        val room = body.x + body.width - x
        var y = sign.y
        g.color = p.fg
        g.font = fitFont(g, 18f * k, nav.distance, room)
        y += g.fontMetrics.ascent - (3 * unit * k).roundToInt()
        g.drawString(nav.distance, x, y)
        g.font = font(Font.BOLD, 7.5f * k)
        y += (4 * unit * k).roundToInt()
        for (line in wrap(g, nav.instruction, room, 2)) {
            if (y + g.fontMetrics.ascent > strip.y) break
            y += g.fontMetrics.ascent
            g.drawString(line, x, y)
            y += (1 * unit * k).roundToInt()
        }
        // The street only when the instruction didn't already name it, and only where it fits.
        if (nav.street.isNotBlank() && !nav.instruction.contains(nav.street, ignoreCase = true)) {
            g.color = p.muted
            g.font = font(Font.PLAIN, 6f * k)
            y += g.fontMetrics.ascent + (1 * unit * k).roundToInt()
            if (y <= strip.y) drawClipped(g, nav.street, x, y, room)
        }

        card(g, strip, p)
        val pad = (5 * unit * k).roundToInt()
        val mid = strip.y + strip.height / 2
        val small = font(Font.BOLD, 4.2f * k)
        val big = font(Font.BOLD, 7f * k)
        val smallBase = mid + g.getFontMetrics(small).ascent * 2 / 5
        val bigBase = mid + g.getFontMetrics(big).ascent * 2 / 5
        if (nav.eta.isNotBlank()) {
            val (label, value) = text.arriveParts(nav.eta)
            g.color = p.muted
            g.font = small
            g.drawString(label.uppercase(), strip.x + pad, smallBase)
            val labelW = if (label.isEmpty()) 0 else g.fontMetrics.stringWidth(label.uppercase()) + (2 * unit).roundToInt()
            g.color = p.fg
            g.font = big
            g.drawString(value, strip.x + pad + labelW, bigBase)
        }
        if (s.speedKmh != null) {
            g.color = p.muted
            g.font = small
            drawRight(g, text.speedUnit, strip.x + strip.width - pad, smallBase)
            val unitW = g.fontMetrics.stringWidth(text.speedUnit) + (2 * unit).roundToInt()
            g.color = p.fg
            g.font = big
            drawRight(g, text.speed, strip.x + strip.width - pad - unitW, bigBase)
        }
    }

    /**
     * The Measures page: every reading the head unit sends for it, in cards of
     * the same size, as many to a row as fit (four for eight readings). Without
     * them (no OBD adapter) it says so, as the media page does with no music.
     */
    private fun paintMeasures(g: Graphics2D, s: ClusterState, text: ClusterText, body: Rectangle, p: Palette) {
        val measures = s.measures
        if (measures.isEmpty()) {
            emptyState(g, body, text.measuresNone, p) { cx, cy, size -> gaugeSign(g, cx, cy, size, p.muted) }
            return
        }
        val gap = (4 * unit).roundToInt()
        // As many to a row as keeps the cards about as wide as they are tall: four for eight on
        // the whole page, two in a tall board slot.
        val columns = when {
            body.width < body.height * 1.6f -> Math.round(Math.sqrt(measures.size * body.width / body.height.toDouble())).toInt().coerceIn(1, measures.size)
            measures.size > 6 -> 4
            measures.size > 4 -> 3
            else -> 2
        }
        val rows = (measures.size + columns - 1) / columns
        val cardW = (body.width - gap * (columns - 1)) / columns
        val cardH = (body.height - gap * (rows - 1)) / rows
        // A short card (a tall board slot, many rows): the caption on one line, smaller, and the figure sized to what is left.
        val short = cardH < 20 * unit
        val pad = ((if (short) 2.5f else 4f) * unit).roundToInt()
        measures.forEachIndexed { i, m ->
            val box = Rectangle(body.x + (i % columns) * (cardW + gap), body.y + (i / columns) * (cardH + gap), cardW, cardH)
            card(g, box, p)
            val inner = box.width - 2 * pad
            g.color = p.muted
            // On two lines: "Liquide de refroidissement" does not fit on one at four to a row;
            // smaller where one word alone is wider than the card ("ACCÉLÉRATEUR").
            val label = m.label.uppercase()
            g.font = fitFont(g, if (short) 3.4f else 4.2f, label.split(' ').maxBy { it.length }, inner - unit.roundToInt())
            val lines = wrap(g, label, inner, if (short) 1 else 2)
            lines.forEachIndexed { line, words ->
                g.drawString(words, box.x + pad, box.y + pad + g.fontMetrics.ascent + line * g.fontMetrics.height)
            }
            val labelBottom = box.y + pad + lines.size * g.fontMetrics.height
            val valueBase = box.y + box.height - pad - ((if (short) 0.5f else 3f) * unit).roundToInt()
            g.color = p.fg
            g.font = fitFont(g, min(13f, (valueBase - labelBottom) / unit * 0.9f), m.value, inner)
            g.drawString(m.value, box.x + pad, valueBase)
        }
    }

    /** A small gauge: an open arc and its needle, for the Measures page with nothing to show. */
    private fun gaugeSign(g: Graphics2D, cx: Float, cy: Float, size: Float, color: Color) {
        val r = size * 0.55f
        g.color = color
        g.stroke = BasicStroke(size * 0.1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.draw(Arc2D.Float(cx - r, cy - r, 2 * r, 2 * r, 210f, -240f, Arc2D.OPEN))
        val a = Math.toRadians(60.0)
        g.draw(Line2D.Float(cx, cy, cx + r * 0.8f * cos(a).toFloat(), cy - r * 0.8f * sin(a).toFloat()))
    }

    /** Nothing to show on this page: a faint sign in a disc, and its words under it. */
    private inline fun emptyState(g: Graphics2D, body: Rectangle, words: String, p: Palette, sign: (Float, Float, Float) -> Unit) {
        val r = 18 * unit
        val cx = body.centerX.toFloat()
        val cy = body.centerY.toFloat() - 8 * unit
        g.color = p.card
        g.fill(Ellipse2D.Float(cx - r, cy - r, 2 * r, 2 * r))
        sign(cx, cy, r)
        g.color = p.muted
        g.font = font(Font.BOLD, 7f)
        drawCentered(g, words, cx.roundToInt(), (cy + r + 12 * unit).roundToInt())
    }

    private fun card(g: Graphics2D, box: Rectangle, p: Palette) {
        val r = (6 * unit).roundToInt()
        g.color = p.card
        g.fillRoundRect(box.x, box.y, box.width, box.height, r, r)
        g.color = p.cardEdge
        g.stroke = BasicStroke(max(1f, unit / 4))
        g.drawRoundRect(box.x, box.y, box.width - 1, box.height - 1, r, r)
    }

    // --- Signs: shapes, not pictures, so they stay sharp at any size ----------------------------

    /** The turn [m] in a [size] box centred on ([cx], [cy]); [under] is the colour behind it. */
    private fun maneuver(g: Graphics2D, m: Maneuver?, cx: Float, cy: Float, size: Float, color: Color, under: Color) {
        val h = size / 2
        g.color = color
        g.stroke = BasicStroke(size * 0.15f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND)
        when (m) {
            Maneuver.LEFT, Maneuver.RIGHT -> {
                val d = if (m == Maneuver.RIGHT) 1f else -1f
                val stem = cx - d * h * 0.4f
                val bendY = cy - h * 0.35f
                g.draw(Path2D.Float().apply {
                    moveTo(stem, cy + h)
                    lineTo(stem, bendY + h * 0.4f)
                    quadTo(stem, bendY, stem + d * h * 0.4f, bendY)
                    lineTo(cx + d * h * 0.35f, bendY)
                })
                head(g, cx + d * h, bendY, if (d > 0) 0.0 else Math.PI, size * 0.55f)
            }
            Maneuver.UTURN -> {
                g.draw(Path2D.Float().apply {
                    moveTo(cx + h * 0.45f, cy + h)
                    lineTo(cx + h * 0.45f, cy - h * 0.25f)
                    curveTo(cx + h * 0.45f, cy - h, cx - h * 0.45f, cy - h, cx - h * 0.45f, cy - h * 0.25f)
                    lineTo(cx - h * 0.45f, cy + h * 0.1f)
                })
                head(g, cx - h * 0.45f, cy + h * 0.7f, Math.PI / 2, size * 0.55f)
            }
            Maneuver.ROUNDABOUT -> {
                // The ring faint, the way through it bold: in at the bottom, round the right, out at the top.
                val r = h * 0.48f
                val oy = cy + h * 0.08f
                val stroke = g.stroke
                g.color = mix(under, color, 0.45f)
                g.stroke = BasicStroke(size * 0.08f)
                g.draw(Ellipse2D.Float(cx - r, oy - r, 2 * r, 2 * r))
                g.color = color
                g.stroke = stroke
                g.draw(Path2D.Float().apply {
                    moveTo(cx, cy + h * 1.05f)
                    lineTo(cx, oy + r)
                    append(Arc2D.Float(cx - r, oy - r, 2 * r, 2 * r, 270f, 180f, Arc2D.OPEN), true)
                    lineTo(cx, cy - h * 0.6f)
                })
                head(g, cx, cy - h * 1.05f, -Math.PI / 2, size * 0.42f)
            }
            Maneuver.ARRIVE -> {
                // A pin with its hole in the colour behind it.
                val r = h * 0.62f
                val top = cy - h
                g.fill(Path2D.Float().apply {
                    moveTo(cx, cy + h)
                    curveTo(cx - r * 0.35f, cy + h * 0.45f, cx - r, cy + h * 0.05f, cx - r, top + r)
                    curveTo(cx - r, top + r * 0.45f, cx - r * 0.55f, top, cx, top)
                    curveTo(cx + r * 0.55f, top, cx + r, top + r * 0.45f, cx + r, top + r)
                    curveTo(cx + r, cy + h * 0.05f, cx + r * 0.35f, cy + h * 0.45f, cx, cy + h)
                    closePath()
                })
                g.color = under
                val hole = r * 0.42f
                g.fill(Ellipse2D.Float(cx - hole, top + r - hole, 2 * hole, 2 * hole))
            }
            Maneuver.STRAIGHT -> {
                g.draw(Line2D.Float(cx, cy + h, cx, cy - h * 0.2f))
                head(g, cx, cy - h, -Math.PI / 2, size * 0.55f)
            }
            null -> navArrow(g, cx, cy, size * 0.85f, color)
        }
    }

    /** A filled arrow head with its tip at ([tipX], [tipY]) pointing at [angle] (radians, y down). */
    private fun head(g: Graphics2D, tipX: Float, tipY: Float, angle: Double, size: Float) {
        val back = size * 0.62f
        val half = size * 0.48f
        val bx = tipX - back * cos(angle).toFloat()
        val by = tipY - back * sin(angle).toFloat()
        val px = -sin(angle).toFloat() * half
        val py = cos(angle).toFloat() * half
        g.fill(Path2D.Float().apply {
            moveTo(tipX, tipY)
            lineTo(bx + px, by + py)
            lineTo(bx - px, by - py)
            closePath()
        })
    }

    /**
     * The compass over the map, top right: a round card with a rim, so it
     * stands out from a dark sky as from a light street, and a needle whose
     * north half is red and carries an upright N. Turned by [headingDeg], the
     * way the camera looks. It was a small dark disc with a white chevron:
     * on the night sky only the chevron showed, a stray triangle, and it read
     * as pointing south as much as north.
     */
    private fun compass(g: Graphics2D, box: Rectangle, pad: Int, headingDeg: Double, p: Palette) {
        val r = 5.5f * unit
        val cx = box.x + box.width - pad - r
        val cy = box.y + pad + r
        g.color = p.card
        g.fill(Ellipse2D.Float(cx - r, cy - r, 2 * r, 2 * r))
        val saved = g.stroke
        g.stroke = BasicStroke(max(1.5f, unit * 0.35f))
        g.color = p.muted
        g.draw(Ellipse2D.Float(cx - r, cy - r, 2 * r, 2 * r))
        g.stroke = saved
        val a = Math.toRadians(-headingDeg)
        // Where north is on the screen, from the centre: up turned by the heading.
        val nx = sin(a).toFloat()
        val ny = -cos(a).toFloat()
        // Across the needle, for its width.
        val wx = -ny
        val wy = nx
        val tip = r * 0.46f
        val half = r * 0.17f
        fun half(sign: Float, color: Color) {
            g.color = color
            g.fill(Path2D.Float().apply {
                moveTo(cx + nx * tip * sign, cy + ny * tip * sign)
                lineTo(cx + wx * half, cy + wy * half)
                lineTo(cx - wx * half, cy - wy * half)
                closePath()
            })
        }
        half(-1f, p.muted)
        half(1f, COMPASS_NORTH)
        // The N stays upright, past the north tip, inside the rim.
        g.font = font(Font.BOLD, 2.6f)
        val fm = g.fontMetrics
        val lx = cx + nx * r * 0.74f
        val ly = cy + ny * r * 0.74f
        val n = "N"
        g.color = COMPASS_NORTH
        g.drawString(n, lx - fm.stringWidth(n) / 2f, ly + fm.ascent * 0.36f)
    }

    /**
     * Where a route will show, while there is none: a start dot and a pin
     * joined by a winding dashed road. An upward arrow there passed for a
     * compass that never turned.
     */
    private fun routeSign(g: Graphics2D, cx: Float, cy: Float, size: Float, color: Color) {
        val h = size / 2
        g.color = color
        val saved = g.stroke
        g.stroke = BasicStroke(max(2f, h * 0.12f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f, floatArrayOf(h * 0.2f, h * 0.18f), 0f)
        val sx = cx - h * 0.55f
        val sy = cy + h * 0.6f
        val ex = cx + h * 0.5f
        val ey = cy - h * 0.35f
        g.draw(Path2D.Float().apply {
            moveTo(sx, sy)
            curveTo(cx + h * 0.6f, cy + h * 0.55f, cx - h * 0.6f, cy - h * 0.05f, ex, ey)
        })
        g.stroke = saved
        val dot = h * 0.16f
        g.fill(Ellipse2D.Float(sx - dot, sy - dot, 2 * dot, 2 * dot))
        // The pin: a round head over a point, a hole in the head.
        val pr = h * 0.24f
        val top = ey - pr * 2.1f
        g.fill(Path2D.Float().apply {
            moveTo(ex, ey)
            lineTo(ex - pr * 0.8f, top + pr * 1.3f)
            lineTo(ex + pr * 0.8f, top + pr * 1.3f)
            closePath()
        })
        g.fill(Ellipse2D.Float(ex - pr, top, 2 * pr, 2 * pr))
    }

    /** The navigation chevron, for a turn the words don't tell. */
    private fun navArrow(g: Graphics2D, cx: Float, cy: Float, size: Float, color: Color) {
        val h = size / 2
        g.color = color
        g.fill(Path2D.Float().apply {
            moveTo(cx, cy - h)
            lineTo(cx + h * 0.75f, cy + h)
            lineTo(cx, cy + h * 0.55f)
            lineTo(cx - h * 0.75f, cy + h)
            closePath()
        })
    }

    private fun musicNote(g: Graphics2D, cx: Float, cy: Float, size: Float, color: Color) {
        val h = size / 2
        val r = h * 0.3f
        val leftStem = cx - h * 0.45f
        val rightStem = cx + h * 0.65f
        g.color = color
        g.fill(Ellipse2D.Float(leftStem - 2 * r + h * 0.07f, cy + h * 0.6f - r * 0.8f, 2 * r, 1.6f * r))
        g.fill(Ellipse2D.Float(rightStem - 2 * r + h * 0.07f, cy + h * 0.4f - r * 0.8f, 2 * r, 1.6f * r))
        g.stroke = BasicStroke(h * 0.14f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER)
        g.draw(Line2D.Float(leftStem, cy + h * 0.6f, leftStem, cy - h * 0.45f))
        g.draw(Line2D.Float(rightStem, cy + h * 0.4f, rightStem, cy - h * 0.7f))
        g.fill(Path2D.Float().apply {
            moveTo(leftStem - h * 0.07f, cy - h * 0.6f)
            lineTo(rightStem + h * 0.07f, cy - h * 0.85f)
            lineTo(rightStem + h * 0.07f, cy - h * 0.55f)
            lineTo(leftStem - h * 0.07f, cy - h * 0.3f)
            closePath()
        })
    }

    private fun playState(g: Graphics2D, cx: Float, cy: Float, size: Float, playing: Boolean, color: Color) {
        val h = size / 2
        g.color = color
        if (playing) {
            // Playing: the pause bars, as on the head unit's own controls.
            val bw = h * 0.6f
            g.fill(RoundRectangle2D.Float(cx - h * 0.8f, cy - h, bw, 2 * h, bw / 2, bw / 2))
            g.fill(RoundRectangle2D.Float(cx + h * 0.8f - bw, cy - h, bw, 2 * h, bw / 2, bw / 2))
        } else {
            g.fill(Path2D.Float().apply {
                moveTo(cx - h * 0.7f, cy - h)
                lineTo(cx + h, cy)
                lineTo(cx - h * 0.7f, cy + h)
                closePath()
            })
        }
    }

    /** A rounded triangle in [tone] with its "!" in [ink], [size] wide, its top at [top]. */
    private fun warningSign(g: Graphics2D, cx: Float, top: Float, size: Float, tone: Color, ink: Color) {
        val h = size * 0.86f
        val tri = Path2D.Float().apply {
            moveTo(cx, top)
            lineTo(cx + size / 2, top + h)
            lineTo(cx - size / 2, top + h)
            closePath()
        }
        g.color = tone
        g.stroke = BasicStroke(size * 0.14f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.draw(tri)
        g.fill(tri)
        g.color = ink
        g.stroke = BasicStroke(size * 0.1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.draw(Line2D.Float(cx, top + h * 0.36f, cx, top + h * 0.64f))
        val dot = size * 0.11f
        g.fill(Ellipse2D.Float(cx - dot / 2, top + h * 0.82f - dot / 2, dot, dot))
    }

    private fun bar(g: Graphics2D, x: Int, y: Int, w: Int, fraction: Float, fill: Color, track: Color) {
        val h = max(4, (1.6f * unit).roundToInt())
        g.color = track
        g.fillRoundRect(x, y, w, h, h, h)
        val filled = (w * fraction.coerceIn(0f, 1f)).roundToInt()
        if (filled <= 0) return
        g.color = fill
        g.fillRoundRect(x, y, max(h, filled), h, h, h)
    }

    private fun drawQr(g: Graphics2D, text: String, x: Int, y: Int, size: Int) {
        val matrix = QRCodeWriter().encode(
            text, BarcodeFormat.QR_CODE, 0, 0,
            mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
        )
        // Whole pixels per module, on a white quiet zone: phones read it off a TV that way.
        val module = max(1, (size - 8 * unit.roundToInt()) / matrix.width)
        val side = module * matrix.width
        val quiet = (size - side) / 2
        g.color = Color.WHITE
        g.fillRect(x, y, size, size)
        g.color = Color.BLACK
        for (my in 0 until matrix.height) for (mx in 0 until matrix.width) {
            if (matrix[mx, my]) g.fillRect(x + quiet + mx * module, y + quiet + my * module, module, module)
        }
    }

    private fun drawCentered(g: Graphics2D, text: String, cx: Int, baseline: Int) =
        g.drawString(text, cx - g.fontMetrics.stringWidth(text) / 2, baseline)

    private fun drawRight(g: Graphics2D, text: String, right: Int, baseline: Int) =
        g.drawString(text, right - g.fontMetrics.stringWidth(text), baseline)

    private fun drawClipped(g: Graphics2D, text: String, x: Int, baseline: Int, maxWidth: Int) =
        g.drawString(clipped(g, text, maxWidth), x, baseline)

    /** [text] cut to [maxWidth] in the current font, ending in an ellipsis when it was. */
    private fun clipped(g: Graphics2D, text: String, maxWidth: Int): String {
        var shown = text
        while (shown.length > 1 && g.fontMetrics.stringWidth(shown) > maxWidth) shown = shown.dropLast(2).trimEnd() + "…"
        return shown
    }

    /** [text] over at most [lines] lines of [maxWidth] in the current font, the last one cut with an ellipsis. */
    private fun wrap(g: Graphics2D, text: String, maxWidth: Int, lines: Int): List<String> {
        val out = mutableListOf<String>()
        var rest = text.trim()
        while (out.size < lines - 1 && g.fontMetrics.stringWidth(rest) > maxWidth) {
            val words = rest.split(' ')
            var line = words[0]
            var used = 1
            while (used < words.size && g.fontMetrics.stringWidth(line + " " + words[used]) <= maxWidth) line += " " + words[used++]
            // One word wider than the line: it is cut on the last line instead.
            if (used == words.size || g.fontMetrics.stringWidth(line) > maxWidth) break
            out += line
            rest = words.drop(used).joinToString(" ")
        }
        if (rest.isNotEmpty()) out += clipped(g, rest, maxWidth)
        return out
    }

    /** Bold at [size], smaller if need be so [sample] fits [maxWidth]. */
    private fun fitFont(g: Graphics2D, size: Float, sample: String, maxWidth: Int): Font {
        val f = font(Font.BOLD, size)
        val w = g.getFontMetrics(f).stringWidth(sample)
        return if (w <= maxWidth || w == 0) f else f.deriveFont(f.size2D * maxWidth / w)
    }

    private fun mmss(ms: Long): String = (ms / 1000).let { "${it / 60}:${(it % 60).toString().padStart(2, '0')}" }

    /** The Retro design's figures: drawn in a fixed-width face. */
    private var mono = false

    /** Font size in hundredths of the screen's short side. */
    private fun font(style: Int, size: Float): Font = fonts.getOrPut(FontKey(mono, style, size)) {
        Font(if (mono) MONO else Font.SANS_SERIF, style, 1).deriveFont(style, size * unit)
    }

    private data class FontKey(val mono: Boolean, val style: Int, val size: Float)

    /** The fonts the pages use, made once: a picture asks for a few dozen, the same ones every time. */
    private val fonts = object : LinkedHashMap<FontKey, Font>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<FontKey, Font>?): Boolean = size > MAX_FONTS
    }

    private inline fun draw(block: (Graphics2D) -> Unit): BufferedImage {
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            g.stroke = BasicStroke(max(2f, unit / 2))
            // A monitor mounted upside down: everything is drawn turned half a turn.
            if (upsideDown) g.transform(java.awt.geom.AffineTransform(-1.0, 0.0, 0.0, -1.0, width.toDouble(), height.toDouble()))
            block(g)
        } finally {
            g.dispose()
        }
        return image
    }

    /** The picture as GStreamer "bgrx" bytes. */
    fun bgrx(): ByteArray {
        val pixels = (image.raster.dataBuffer as java.awt.image.DataBufferInt).data
        val out = ByteArray(pixels.size * 4)
        // 0x00RRGGBB written little-endian is B, G, R, x: one bulk copy (a loop of bytes took 35 ms a picture on a Pi 3B).
        java.nio.ByteBuffer.wrap(out).order(java.nio.ByteOrder.LITTLE_ENDIAN).asIntBuffer().put(pixels)
        return out
    }

    /** The cluster's colours by night (pure black: an IPS panel glows on anything lighter) and by day. */
    private class Palette(val night: Boolean, dashAccent: Color, design: Design) {
        // Retro is amber on black by night and a grey-green LCD by day, whatever the dashboard's accent.
        private val retro = design == Design.RETRO
        val accent = if (!retro) dashAccent else if (night) Color(0xFFB000) else Color(0x1A2310)
        val bg = pick(BG_NIGHT, Color(0xEEF1F4), BG_NIGHT, Color(0xB7C29C))
        val card = pick(Color(0x0E1217), Color(0xFFFFFF), Color(0x130C00), Color(0xADB892))
        val cardEdge = pick(Color(0x1A2129), Color(0xD9DFE5), Color(0x2A1B00), Color(0x98A47C))
        val track = pick(Color(0x1D252E), Color(0xD5DBE1), Color(0x2E2106), Color(0x9EAA82))
        /** Retro's unlit segments. */
        val ghost = pick(Color(0x1D252E), Color(0xD5DBE1), Color(0x181001), Color(0xAEBA94))
        val fg = pick(TEXT, Color(0x111418), Color(0xFFB000), Color(0x1A2310))
        val muted = pick(MUTED, Color(0x5B6570), Color(0x9A6A00), Color(0x4A5536))
        /** Amber is lost on the day palette's near-white: a burnt orange reads there. */
        val warn = pick(Color(0xFFB300), Color(0xA84300), Color(0xFF6D00), Color(0x8A3A00))
        val critical = pick(Color(0xFF5252), Color(0xC62828), Color(0xFF3D00), Color(0x9A1010))
        val cold = pick(Color(0x64B5F6), Color(0x1565C0), Color(0x4FC3F7), Color(0x0D3C7A))
        /** The Dials' needles: the warm orange of real ones. */
        val needle = if (night) Color(0xFF6E40) else Color(0xE64A19)

        private fun pick(night: Color, day: Color, retroNight: Color, retroDay: Color) =
            if (retro) (if (this.night) retroNight else retroDay) else if (this.night) night else day
    }

    private companion object {
        /** The compass's north half and its N: a compass red, the same in every look. */
        val COMPASS_NORTH = Color(0xE5, 0x39, 0x35)
        val BG_NIGHT = Color(0x000000)
        val TEXT = Color(0xF2F5F8)
        val MUTED = Color(0x8A96A3)

        private const val MAX_FONTS = 64

        /** A fixed-width face the Pi has (DejaVu), else one this machine has. */
        val MONO: String by lazy {
            val have = runCatching { java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet() }.getOrDefault(emptySet())
            listOf("DejaVu Sans Mono", "Consolas").firstOrNull { it in have } ?: Font.MONOSPACED
        }

        /** Retro's rev segments are all lit here. */
        const val RETRO_RPM_FULL = 5_000f

        /** How far along the route the map fetches tiles ahead of the car. */
        const val ROUTE_AHEAD_M = 3_000.0

        /** The sharpest tiles the tilted map asks for, near the car (one zoom above the flat map's, at most this): about 40 cm a pixel. */
        const val MAX_CHASE_ZOOM = 18
        const val ROUTE_AHEAD_TILES = 40

        /** Coolant below this is still warming up (blue), from these on warm (amber) and hot (red). */
        const val COLD_C = 60
        const val WARM_C = 105
        const val HOT_C = 110
        const val LOW_FUEL_PCT = 10

        /** [a] moved [t] of the way to [b]. */
        fun mix(a: Color, b: Color, t: Float) = Color(
            (a.red + (b.red - a.red) * t).roundToInt(),
            (a.green + (b.green - a.green) * t).roundToInt(),
            (a.blue + (b.blue - a.blue) * t).roundToInt()
        )

        /** Near-black or white, whichever reads on [accent]. */
        fun onAccent(accent: Color): Color =
            if (accent.red * 0.299 + accent.green * 0.587 + accent.blue * 0.114 > 170) Color(0x0B0F14) else Color.WHITE
    }
}

/** The cluster's look ([ClusterState.design]); an older head unit sends none and gets [CARDS]. */
internal enum class Design {
    /** A speed ring and the readings in cards. */
    CARDS,

    /** Round gauges with needles, speed and revs. */
    DIALS,

    /** One big number, the readings in a line under it. */
    LARGE,

    /** An old digital dash: segments and amber digits. */
    RETRO;

    companion object {
        fun of(name: String?): Design = entries.firstOrNull { it.name == name } ?: CARDS
    }
}

/**
 * The turn the navigation app's words describe, for the sign beside them.
 * The words come in the head unit's language, so each one is looked for in all
 * of Dashwheel's; null when none is found (the sign is then a plain arrow).
 */
internal enum class Maneuver {
    LEFT, RIGHT, STRAIGHT, UTURN, ROUNDABOUT, ARRIVE;

    companion object {
        private val ROUNDABOUT_WORDS = listOf("roundabout", "rond-point", "rond point", "giratoire", "kreisverkehr", "rotonda", "glorieta", "rotunda", "rotatória", "rotonde", "rondo", "körforgalom", "žiedin", "кольц")
        private val UTURN_WORDS = listOf("u-turn", "u turn", "demi-tour", "wenden", "cambio de sentido", "inversione", "retorno", "keer om", "omkeren", "zawróć", "zawroc", "forduljon vissza", "fordulj vissza", "apsisuk", "разворот")
        private val ARRIVE_WORDS = listOf("destination", "you have arrived", "arrivée", "vous êtes arrivé", "ziel", "destino", "bestemming", "cel podróży", "úti cél", "megérkezett", "atvykote", "kelionės tikslas", "пункт назначения")
        private val LEFT_WORDS = listOf("left", "gauche", "links", "izquierda", "sinistra", "esquerda", "lewo", "balra", "kair", "налево", "левее", "влево")
        private val RIGHT_WORDS = listOf("right", "droite", "rechts", "derecha", "destra", "direita", "prawo", "jobbra", "dešin", "направо", "правее", "вправо")
        private val STRAIGHT_WORDS = listOf("straight", "continue", "tout droit", "continuez", "geradeaus", "recto", "dritto", "em frente", "rechtdoor", "prosto", "egyenesen", "tiesiai", "прямо")

        /** The turn as the head unit names it ([ClusterState.maneuver]); null for a name this display doesn't know. */
        fun named(name: String): Maneuver? = entries.firstOrNull { it.name == name }

        fun of(instruction: String): Maneuver? {
            val s = instruction.lowercase()
            if (ROUNDABOUT_WORDS.any { it in s }) return ROUNDABOUT
            if (UTURN_WORDS.any { it in s }) return UTURN
            if (ARRIVE_WORDS.any { it in s }) return ARRIVE
            // The first direction named is the turn ("Keep right to continue on A6": right).
            return listOf(LEFT to LEFT_WORDS, RIGHT to RIGHT_WORDS, STRAIGHT to STRAIGHT_WORDS)
                .mapNotNull { (m, words) -> words.mapNotNull { w -> s.indexOf(w).takeIf { it >= 0 } }.minOrNull()?.let { m to it } }
                .minByOrNull { it.second }?.first
        }
    }
}

/**
 * What the cluster prints from a [ClusterState]: the figures in the driver's
 * units and the head unit's own words when it sent them, else metric, 24 h and
 * English as before (an older head unit says none of it).
 */
internal class ClusterText(private val s: ClusterState, words: ClusterState.Labels? = Words.labels) {
    /** The head unit's words: with each reading (an older head unit), else as sent once ([Words.labels]). */
    private val labels = s.labels ?: words

    fun clock(now: Long, zone: ZoneId = ZoneId.systemDefault()): String = clockText(now, s.clock12, zone)

    /** The doors' warning, or null with none open. */
    val open: String?
        get() = s.open.takeIf { it.isNotEmpty() }?.let { fill(labels?.open, "Open: %s", it.joinToString(", ")) }

    /** The doors face before the car box reported anything; null once it has (or from an older head unit). */
    val doorsUnknown: String? get() = if (s.doorsKnown) null else labels?.doorsUnknown ?: "Waiting for car box data"

    /** The directions face while [app] runs with nothing to say. */
    fun navRunning(app: String): String = fill(labels?.navRunning, "%s is running", app)

    /** The speed in the driver's unit, or null when the head unit has none. */
    val speedShown: Int? get() = s.speedKmh?.let { distance(it) }

    val speed: String get() = speedShown?.toString() ?: "--"

    val speedUnit: String get() = s.speedUnit ?: if (s.imperial) "mph" else "km/h"

    enum class Kind { RPM, COOLANT, FUEL, RANGE }

    class Reading(val value: String, val label: String, val kind: Kind)

    /** Each figure the car reports, with its caption. */
    val cards: List<Reading>
        get() = buildList {
            s.rpm?.let { add(Reading("$it", rpmLabel, Kind.RPM)) }
            s.coolantC?.let { add(Reading(if (s.fahrenheit) "${(it * 1.8 + 32).roundToInt()}°F" else "$it°", labels?.coolant ?: "coolant", Kind.COOLANT)) }
            s.fuelPct?.let { add(Reading("$it%", labels?.fuel ?: "fuel", Kind.FUEL)) }
            s.rangeKm?.let { add(Reading(distance(it).toString(), labels?.range ?: if (s.imperial) "mi range" else "km range", Kind.RANGE)) }
        }

    /** The reading of [kind], "--" when the car doesn't give it. */
    fun card(kind: Kind): Reading = cards.firstOrNull { it.kind == kind } ?: Reading(
        "--",
        when (kind) {
            Kind.RPM -> rpmLabel
            Kind.COOLANT -> labels?.coolant ?: "coolant"
            Kind.FUEL -> labels?.fuel ?: "fuel"
            Kind.RANGE -> labels?.range ?: if (s.imperial) "mi range" else "km range"
        },
        kind
    )

    /** [cards] as figure to caption. */
    val readings: List<Pair<String, String>> get() = cards.map { it.value to it.label }

    val rpmLabel: String get() = labels?.rpm ?: "rpm"

    val nothingPlaying: String get() = labels?.nothingPlaying ?: "Nothing playing"

    val noRoute: String get() = labels?.noRoute ?: "No route"

    val measuresNone: String get() = labels?.measuresNone ?: "Waiting for the OBD adapter"

    val noPosition: String get() = labels?.noPosition ?: "Waiting for the GPS"

    val noTiles: String get() = labels?.noTiles ?: "No map tiles: set tile_url on the card"

    fun arrive(eta: String): String = fill(labels?.arrive, "Arrive %s", eta)

    /** The arrival line split into its words and the time, so the time can be drawn bigger. */
    fun arriveParts(eta: String): Pair<String, String> {
        val line = labels?.arrive?.takeIf { "%s" in it } ?: "Arrive %s"
        return line.replace("%s", "").trim().trimEnd(':').trim() to eta
    }

    private fun distance(km: Int): Int = if (s.imperial) (km / KM_PER_MILE).roundToInt() else km

    /** [value] in the head unit's line; a line without its %s would lose the value, so English then. */
    private fun fill(line: String?, english: String, value: String): String =
        (line?.takeIf { "%s" in it } ?: english).replace("%s", value)

    private companion object {
        const val KM_PER_MILE = 1.609344
    }
}

private val CLOCK_24 = DateTimeFormatter.ofPattern("HH:mm")
private val CLOCK_12 = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)

/** "14:05", or "2:05 PM" on a 12-hour clock. */
internal fun clockText(now: Long, clock12: Boolean, zone: ZoneId = ZoneId.systemDefault()): String =
    (if (clock12) CLOCK_12 else CLOCK_24).withZone(zone).format(Instant.ofEpochMilli(now))

/**
 * The board's arrangements ([ClusterState.Layout.arrangement]) as boxes in a
 * body, the big one first, as the head unit lays its own slots out.
 */
internal object Board {
    private const val BIG = 1.55f

    fun boxes(arrangement: String, body: Rectangle, gap: Int): List<Rectangle>? = when (arrangement) {
        "ONE" -> listOf(body)
        "BIG_SIDE" -> columns(body, gap, BIG, 1f)
        "HALVES" -> columns(body, gap, 1f, 1f)
        "THREE" -> columns(body, gap, 1f, 1f, 1f)
        "BIG_STACK" -> columns(body, gap, BIG, 1f).let { (big, side) -> listOf(big) + rows(side, gap, 1f, 1f) }
        "GRID" -> rows(body, gap, 1f, 1f).flatMap { columns(it, gap, 1f, 1f) }
        else -> null
    }

    private fun columns(box: Rectangle, gap: Int, vararg weights: Float): List<Rectangle> {
        val room = box.width - gap * (weights.size - 1)
        val total = weights.sum()
        var x = box.x
        return weights.mapIndexed { i, w ->
            val width = if (i == weights.lastIndex) box.x + box.width - x else (room * w / total).roundToInt()
            Rectangle(x, box.y, width, box.height).also { x += width + gap }
        }
    }

    private fun rows(box: Rectangle, gap: Int, vararg weights: Float): List<Rectangle> {
        val room = box.height - gap * (weights.size - 1)
        val total = weights.sum()
        var y = box.y
        return weights.mapIndexed { i, w ->
            val height = if (i == weights.lastIndex) box.y + box.height - y else (room * w / total).roundToInt()
            Rectangle(box.x, y, box.width, height).also { y += height + gap }
        }
    }
}
