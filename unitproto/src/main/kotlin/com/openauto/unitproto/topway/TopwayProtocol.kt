package com.openauto.unitproto.topway

/**
 * The Topway TS firmware's MCU link, as its car app (com.tw.car 880_0000_260911,
 * a TS10.2.2 unit's copy) uses it: the framework's `android.tw.john.TWUtil`,
 * opened on a list of channels (`open(short[])`, 0 for done), started, and
 * handed a Handler (`addHandler(tag, handler)`). Each message is one channel
 * ([android.os.Message.what]), with [android.os.Message.arg1] and, for the
 * canbox's, its frame's bytes in [android.os.Message.obj].
 *
 * Read from the car app's bytecode, not from Topway: what is not named here
 * is not known. Dashwheel only listens; it never writes to the MCU. It only
 * asks the firmware's command service ([COMMAND_PACKAGE]) to play, pause or skip.
 */
object TopwayProtocol {
    /** The framework class every Topway app talks to the MCU through. */
    const val TWUTIL_CLASS = "android.tw.john.TWUtil"

    /** Reverse: arg1 1 in reverse, 0 out of it (the car app's CarService). */
    const val CH_REVERSE = 0x20B
    /** Handled by the car app exactly as [CH_REVERSE] (another source of the same news). */
    const val CH_REVERSE_ALT = 0x9F1C
    /** The canbox: arg1 the frame's type, obj its bytes (0x40 and 0x21 carry the Jeep's settings). */
    const val CH_CANBOX = 0x501
    /** The car the canbox was set up for: obj's byte 12 tells which. */
    const val CH_CAR_TYPE = 0x10B

    /** What Dashwheel opens: only what it reads. */
    val LISTENED = shortArrayOf(CH_REVERSE.toShort(), CH_REVERSE_ALT.toShort(), CH_CANBOX.toShort(), CH_CAR_TYPE.toShort())

    /** Finder keys: one byte value per channel and frame type ("tw:1281:64[]"), sliced into bytes and bits. */
    const val PREFIX = "tw"

    /** Whether [arg1] of a reverse channel says reverse (true), not reverse (false), or nothing the car app reads (null). */
    fun reverse(arg1: Int): Boolean? = when (arg1) {
        1 -> true
        0 -> false
        else -> null
    }

    fun frameKey(channel: Int, type: Int): String = "$PREFIX:$channel:$type[]"

    /** The bytes as the finder reads byte values: two hex digits each, spaced. */
    fun hex(bytes: ByteArray): String = bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    /** The channel is a reverse one ([CH_REVERSE], [CH_REVERSE_ALT]). */
    fun isReverse(channel: Int): Boolean = channel == CH_REVERSE || channel == CH_REVERSE_ALT

    /** The channels whose bytes go to the finder. */
    fun carriesFrames(channel: Int): Boolean = channel == CH_CANBOX || channel == CH_CAR_TYPE

    /**
     * The firmware's command service (com.tw.service.xt 2022.02.17, a TS unit's copy), exported
     * with no permission: the unit's own apps send play, pause, next and previous through it, and
     * it hands each to the source playing (Bluetooth music, the unit's music player, video, radio),
     * else sends a media key. The Bluetooth app takes no media key and no session command, so
     * this is the one way to steer it. Read from the service's bytecode.
     */
    const val COMMAND_PACKAGE = "com.tw.service.xt"
    const val COMMAND_CLASS = "com.tw.service.xt.CommandService"
    const val COMMAND_BIND_ACTION = "com.tw.service.xt.CommandService.Bind"
    const val COMMAND_DESCRIPTOR = "com.tw.service.xt.aidl.ITWCommandAidl"
    /** ITWCommandAidl's transactions: no arguments, an exception-only reply. */
    const val TX_MEDIA_NEXT = 55
    const val TX_MEDIA_PREVIOUS = 56
    const val TX_MEDIA_PLAY = 57
    const val TX_MEDIA_PAUSE = 58
    /** The Bluetooth app whose session takes no command ([COMMAND_PACKAGE] steers it). */
    const val BT_PACKAGE = "com.tw.bt"

    // --- The radio (com.tw.radio 78cc, read from its bytecode) -----------------------------
    //
    // It publishes no media session on this firmware: what it plays comes from the MCU, the
    // way the unit's own media bar (SystemUI) reads it, and its buttons go through the
    // command service, which hands them to the radio app.

    const val RADIO_PACKAGE = "com.tw.radio"
    /**
     * TWUtil's device for the tuner: the radio app opens `TWUtil(1)`. Opened without one
     * (the general MCU device, [TopwayBus]'s), the tuner's channels only bring raw frames,
     * never the decoded frequency and presets below.
     */
    const val RADIO_DEVICE = 1
    /** The unit's audio source: arg1 the source, [SOURCE_RADIO] for the tuner, 8 for Bluetooth. */
    const val CH_SOURCE = 0x301
    const val SOURCE_RADIO = 1
    /** The tuner: arg1 the field, arg2 its value, obj the station's RDS name on [TUNER_FREQ]. */
    const val CH_TUNER = 0x401
    const val TUNER_BAND = 1
    /** In 10 kHz on FM (9800 = 98.00 MHz), kHz on AM. */
    const val TUNER_FREQ = 2
    /** The preset playing, -1 for none. */
    const val TUNER_PRESET = 4
    /** One preset: arg1's low byte its slot, arg2 its frequency, obj its RDS name. */
    const val CH_PRESET = 0x402
    /** The station's RDS text (obj). */
    const val CH_RDS_TEXT = 0x405
    /** The tuner's band numbers: 0 FM1, 1 FM2, 2 AM. */
    const val TUNER_AM = 2
    /** Written to [CH_TUNER] alone, it asks the MCU for the tuner's state again, as the radio app does when it opens. */
    const val ASK_AGAIN = 255
    /** The radio's play/pause key, as its own media button handler sends it: [CH_KEY], 1, [KEY_PLAY_PAUSE]. */
    const val CH_KEY = 0x201
    const val KEY_PLAY_PAUSE = 20
    val RADIO_LISTENED = shortArrayOf(CH_SOURCE.toShort(), CH_TUNER.toShort(), CH_PRESET.toShort(), CH_RDS_TEXT.toShort())

    /** ITWCommandAidl's radio transactions, handed to the radio app by the service. */
    const val TX_RADIO_NEXT = 11
    const val TX_RADIO_PREVIOUS = 12
    const val TX_RADIO_SEEK_UP = 13
    const val TX_RADIO_SEEK_DOWN = 14
    /** One int: the frequency in the tuner's units. */
    const val TX_RADIO_TUNE = 15

    /** The tuner's band as Dashwheel numbers them (0..2 FM1..FM3, 3..5 AM1..AM3). */
    fun dashBand(tunerBand: Int): Int = if (tunerBand == TUNER_AM) 3 else tunerBand.coerceIn(0, 2)

    /** The car info service ([INFO_PACKAGE]), exported, its permission a normal one: bound like the car app binds it. */
    const val INFO_PACKAGE = "com.tw.carinfoservice"
    const val INFO_BIND_ACTION = "com.tw.carinfoservice.CarService.Bind"
    const val INFO_PERMISSION = "com.tw.carinfoservice.permission.USE_AIDL"
    const val INFO_DESCRIPTOR = "com.tw.carinfoservice.CarServiceAidl"
    const val CALLBACK_DESCRIPTOR = "com.tw.carinfoservice.CarServiceCallBack"
    /** The service's transaction that takes a callback binder (and 2 that lets it go). */
    const val TX_REGISTER = 1
    const val TX_UNREGISTER = 2

    /** Sent ahead of every other callback; says nothing of the car by itself. */
    const val CB_PREAMBLE = 63
    /** Engine speed (the service logs it as such); its scale unconfirmed. */
    const val CB_ENGINE_SPEED = 16
    /** Next to the service's "Speed": the car's speed, scale unconfirmed. */
    const val CB_SPEED = 15
    /** The parking radar: 16 values. */
    const val CB_RADAR = 59
    /** The steering's guide-line track. */
    const val CB_TRACK = 60

    /**
     * What each callback carries, in order, as the service's proxy writes it
     * (version 251230): i an int, f a float, s a text. A code not listed is
     * not read.
     */
    val CALLBACK_ARGS: Map<Int, String> = mapOf(
        1 to "fs",
        2 to "i",
        3 to "i",
        4 to "fs",
        5 to "fs",
        6 to "fs",
        7 to "fs",
        8 to "i",
        9 to "i",
        10 to "i",
        11 to "i",
        12 to "i",
        13 to "i",
        14 to "i",
        15 to "i",
        16 to "i",
        17 to "is",
        18 to "fs",
        19 to "fs",
        20 to "fs",
        21 to "f",
        22 to "i",
        23 to "i",
        24 to "i",
        25 to "i",
        26 to "i",
        27 to "i",
        28 to "i",
        29 to "fs",
        30 to "fs",
        31 to "fs",
        32 to "fs",
        33 to "fs",
        34 to "i",
        35 to "i",
        36 to "i",
        37 to "fs",
        38 to "fs",
        39 to "fs",
        40 to "fs",
        41 to "fs",
        42 to "fs",
        43 to "fs",
        44 to "fs",
        45 to "fs",
        46 to "fs",
        47 to "s",
        48 to "i",
        49 to "is",
        50 to "ii",
        51 to "ii",
        52 to "ii",
        53 to "ii",
        54 to "is",
        55 to "is",
        56 to "is",
        57 to "is",
        58 to "i",
        59 to "iiiiiiiiiiiiiiii",
        60 to "i",
        61 to "i",
        62 to "fs",
        63 to "i",
        64 to "fs",
        65 to "i",
        66 to "i"
    )

    /**
     * The finder's keys for one callback: "tw:cb:15#0" = "57" for each number;
     * a float with its text beside it (the service names some values so) is
     * kept under that text, "tw:cb:4:Speed" = "12.5". Texts alone go as they are.
     */
    fun callbackValues(code: Int, args: List<Any?>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val types = CALLBACK_ARGS[code] ?: return out
        if (types == "fs" && args.size == 2 && args[1] is String) {
            out["$PREFIX:cb:$code:${args[1]}"] = number(args[0])
            return out
        }
        args.forEachIndexed { i, v -> if (v != null) out["$PREFIX:cb:$code#$i"] = if (v is String) v else number(v) }
        return out
    }

    private fun number(v: Any?): String = when (v) {
        is Float -> if (v == v.toLong().toFloat()) v.toLong().toString() else v.toString()
        else -> v.toString()
    }
}
