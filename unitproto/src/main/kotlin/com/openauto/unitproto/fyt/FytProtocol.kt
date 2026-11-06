package com.openauto.unitproto.fyt

/*
 * The FYT / SYU firmware's app-facing interface, as the community worked it
 * out (AxesOfEvil/FYTCanbusMonitor, Pacjonek/fyt-dialer, open-headunit,
 * cssbruno/honda-customizer): one "toolkit" service in the firmware's main
 * server app, com.syu.ms, handing out one binder per module (radio, Bluetooth,
 * CAN bus...). Each module takes commands, answers reads, and pushes updates
 * to registered callbacks; everything travels as an update code with int,
 * float and string arrays.
 *
 * The Android side (binding, parcels) lives in the app; this file holds the
 * names, numbers and the small pure rules, so they are unit-tested here and
 * shared with the fake firmware (:fakeunit).
 */
object FytProtocol {
    /** The firmware's main server app, which hosts the toolkit service. */
    const val PACKAGE = "com.syu.ms"
    /** The toolkit service's class and the action its intent filter declares. */
    const val SERVICE_CLASS = "app.ToolkitService"
    const val ACTION = "com.syu.ms.toolkit"

    const val DESCRIPTOR_TOOLKIT = "com.syu.ipc.IRemoteToolkit"
    const val DESCRIPTOR_MODULE = "com.syu.ipc.IRemoteModule"
    const val DESCRIPTOR_CALLBACK = "com.syu.ipc.IModuleCallback"

    /** IRemoteToolkit: getRemoteModule(int moduleCode) → IRemoteModule. */
    const val TX_GET_MODULE = 1

    /** IRemoteModule: cmd(int code, int[] ints, float[] flts, String[] strs). */
    const val TX_CMD = 1
    /** IRemoteModule: get(int code, int[], float[], String[]) → ModuleObject. */
    const val TX_GET = 2
    /** IRemoteModule: register(IModuleCallback cb, int updateCode, int flag); flag 1 also pushes the current value. */
    const val TX_REGISTER = 3
    /** IRemoteModule: unregister(IModuleCallback cb, int updateCode). */
    const val TX_UNREGISTER = 4

    /** IModuleCallback: update(int updateCode, int[] ints, float[] flts, String[] strs); one way. */
    const val TX_UPDATE = 1

    /** The value of register's flag that asks for the current value at once. */
    const val REGISTER_WITH_VALUE = 1

    /**
     * Whether a module transaction's reply carries anything to read. Some
     * firmwares (Joying's 2.23 com.syu.ms, as honda-customizer found) answer
     * cmd, register and unregister with a zero-length parcel: reading an
     * exception header from it would throw. Only a read answers with a body.
     */
    fun moduleReplyHasBody(tx: Int): Boolean = tx == TX_GET

    /** The ACC broadcasts the firmware sends every app (receivable by anyone; sending one needs a system app). */
    const val ACTION_ACC_ON = "com.fyt.boot.ACCON"
    const val ACTION_ACC_OFF = "com.fyt.boot.ACCOFF"

    /** The firmware's media command relay (stock players' next / previous), extras unverified. */
    const val ACTION_EXTERNAL_COMMAND = "com.syu.ms.action.external_command"

    /** Set to "2", the app binding the toolkit gets the steering wheel keys through module MAIN (open-headunit's CarFYTReceiver). */
    const val PROP_CARLINK_TYPE = "sys.carlink.type"
    const val CARLINK_TAKE_KEYS = "2"

    /** The firmware's own apps, for package visibility and the report. */
    val PACKAGES = setOf(PACKAGE, "com.syu.canbus", "com.syu.carlink", "com.syu.air", "com.syu.bt", "com.syu.radio", "com.android.launcher4", "com.android.launcher6")
}

/** The toolkit's module codes (getRemoteModule). */
object FytModules {
    const val MAIN = 0
    const val RADIO = 1
    const val BT = 2
    const val DVD = 3
    const val SOUND = 4
    const val IPOD = 5
    const val TV = 6
    const val CANBUS = 7
    const val TPMS = 8
    const val DVR = 9
    const val STEER = 10
    const val CUSTOMER = 11
    const val OBD = 12
    const val TEST = 13
    const val CAN_UPDATE = 14
    const val AMP = 15
    const val EMITTER = 16
    const val GSENSOR = 17

    /** A module's name, for logs and the finder. */
    fun name(code: Int): String = when (code) {
        MAIN -> "main"; RADIO -> "radio"; BT -> "bt"; DVD -> "dvd"; SOUND -> "sound"; IPOD -> "ipod"; TV -> "tv"
        CANBUS -> "canbus"; TPMS -> "tpms"; DVR -> "dvr"; STEER -> "steer"; CUSTOMER -> "customer"; OBD -> "obd"
        TEST -> "test"; CAN_UPDATE -> "canup"; AMP -> "amp"; EMITTER -> "emitter"; GSENSOR -> "gsensor"
        else -> "module$code"
    }
}

/** Module MAIN's update codes. */
object FytMain {
    /**
     * ints[0]: the illumination input, 1 with the car's lights on (the firmware dims the screen and goes to its
     * night mode on it). Seen on a UIS8581A with a VW MQB decoder; side lights and dipped beam read the same.
     */
    const val U_ILL = 4
    /** A steering wheel or panel key: ints[0] is the key code (open-headunit); 576 is the voice key there. */
    const val U_KEY = 133
    const val KEY_VOICE = 576
}

/**
 * Module SOUND's update codes. A UIS8581A firmware keeps the wheel's volume and mute keys to itself even with
 * the keys taken: they only show here, as the volume and mute they set.
 */
object FytSound {
    const val U_VOLUME = 2
    /** ints[0]: 1 muted, 0 not. */
    const val U_MUTE = 3
    /** Made-up key codes (no firmware key has them) for mute going on and off, so the wheel map can bind them. */
    const val KEY_MUTED = 100_401
    const val KEY_UNMUTED = 100_400
}

/** Module BT's command and update codes (fyt-dialer, from the decompiled com.syu.bt). */
object FytBt {
    /** strs[0] = the number to call (fyt-dialer). */
    const val C_DIAL = 7
    const val C_REDIAL = 8
    const val C_PICKUP = 9
    const val C_HANG = 10
    /** strs[0] = the connected phone's name (or strs[0] = phone index "0"/"1" and strs[1] = name on dual-phone units). */
    const val U_PHONE_NAME = 7
    /** ints[0] = the HFP state: [STATE_CONNECTED] idle and connected, [STATE_RINGING] incoming. The others are unconfirmed. */
    const val U_PHONE_STATE = 9
    const val STATE_CONNECTED = 2
    const val STATE_RINGING = 4
}

/**
 * Module CANBUS's generic update codes (the 1000s, the same on every car;
 * FYTCanbusMonitor's CanbusIdCodes). Codes below 1000 are per car and per
 * CAN decoder (com.syu.canbus): speed may be 101 on one car and nothing on
 * another, which is what the Signal Finder is for.
 */
object FytCanbus {
    /** ints[0]: the CAN decoder's id, [decoderOf] the box type and [carOf] the car it was set up for. */
    const val U_CANBUS_ID = 1000
    const val U_EXIST_DOOR = 1004
    const val U_CANBUS_VER = 1005
    const val U_EXIST_AIR = 1007
    const val U_EXIST_TEMP_OUT = 1012
    /** Which camera the unit shows; believed to say reverse, unconfirmed. */
    const val U_CAMERA_MODE = 1013
    const val U_SHOW_BACKCAR_HOST = 1030
    /** ints[0]: the car's speed, unit unconfirmed (km/h expected). */
    const val U_CUR_SPEED = 1031
    /** ints[0]: engine revolutions per minute, unconfirmed. */
    const val U_ENGINE_SPEED = 1032
    const val U_GPS_ANGLE = 1033

    /** The generic codes a Dashwheel subscribes to on every FYT unit. */
    val GENERIC = intArrayOf(U_CANBUS_ID, U_EXIST_DOOR, U_CANBUS_VER, U_EXIST_AIR, U_EXIST_TEMP_OUT, U_CAMERA_MODE, U_CUR_SPEED, U_ENGINE_SPEED, FytFrames.U_FRAMES)

    /** The range the Signal Finder's sniffer subscribes to while it is open: every per-car code and the generic ones. */
    val SNIFF_RANGE = 0..1100

    /** The low 16 bits of the CAN bus id: the decoder box type. */
    fun decoderOf(canbusId: Int): Int = canbusId and 0xFFFF

    /** The high 16 bits of the CAN bus id: the car make / model it was set up for. */
    fun carOf(canbusId: Int): Int = (canbusId ushr 16) and 0xFFFF

    /** A plausible speed in km/h from an update's first int, or null. */
    fun speedKmh(ints: IntArray?): Float? = ints?.firstOrNull()?.takeIf { it in 0..400 }?.toFloat()

    /** A plausible engine speed from an update's first int, or null. */
    fun rpm(ints: IntArray?): Int? = ints?.firstOrNull()?.takeIf { it in 0..12_000 }
}

/**
 * Module CANBUS's code 1019: the decoder's own frames passed through as they
 * come, several kinds under the one code. ints[0] is constant, ints[1] the
 * frame's id, ints[2] its payload length, the payload from ints[3]. Read on a
 * UIS8581A with an RZC VW MQB decoder (Skoda Fabia 3, issue #146).
 */
object FytFrames {
    const val U_FRAMES = 1019
    const val FRAME_REAR_RADAR = 0x22
    const val FRAME_FRONT_RADAR = 0x23
    const val FRAME_CLOCK = 0x26
    /** A distance byte that sees nothing. */
    private const val NOTHING = 255

    fun frameId(ints: IntArray?): Int? = ints?.getOrNull(1)

    fun isRadar(ints: IntArray?): Boolean = frameId(ints).let { it == FRAME_REAR_RADAR || it == FRAME_FRONT_RADAR }

    /**
     * A radar frame's four sensors, left to right, on Dashwheel's scale: null
     * for a sensor the car doesn't have (level nibble 0), 0 for nothing seen,
     * else 1 (closest) to 10 from the distance byte, taken for cm. Null when
     * [ints] is no radar frame.
     */
    fun radarLevels(ints: IntArray?): List<Int?>? {
        if (ints == null || !isRadar(ints) || ints.size < 9 || ints[2] < 6) return null
        val nibbles = listOf((ints[7] shr 4) and 0xF, ints[7] and 0xF, (ints[8] shr 4) and 0xF, ints[8] and 0xF)
        return (0 until 4).map { i ->
            val cm = ints[3 + i]
            when {
                nibbles[i] == 0 -> null
                cm == NOTHING || cm <= 0 -> 0
                else -> levelOfCm(cm)
            }
        }
    }

    /** The inverse of the app's ObstacleMemory.metres: level 1 is about 30 cm, 10 about 1.5 m. */
    fun levelOfCm(cm: Int): Int = (Math.round((cm / 100.0 - 0.3) / 0.13).toInt() + 1).coerceIn(1, 10)
}

/**
 * How an FYT update is keyed for the Signal Finder: "fyt:<module>:<code>#<i>"
 * for the i-th int, "#f<i>" for a float and "#s<i>" for a string. One piece
 * each (the finder slices no bytes out of them).
 */
object FytKey {
    const val PREFIX = "fyt"

    /** [sub] is the sub-code of a code that carries several values in turn ([MUX]), null for a plain one. */
    data class Parsed(val module: Int, val code: Int, val kind: Char, val index: Int, val sub: Int? = null)

    /**
     * CANBUS codes that carry several values in turn, with the index of the
     * int telling which: 1019 by its frame id, 1049 (lights, doors...) by its
     * first int. Their ints are keyed once more per sub-code,
     * "fyt:7:1049[11]#1", so a taught signal reads only its own value.
     */
    private val MUX = mapOf(FytModules.CANBUS to mapOf(FytFrames.U_FRAMES to 1, 1049 to 0))

    fun int(module: Int, code: Int, index: Int) = "$PREFIX:$module:$code#$index"
    fun subInt(module: Int, code: Int, sub: Int, index: Int) = "$PREFIX:$module:$code[$sub]#$index"
    fun float(module: Int, code: Int, index: Int) = "$PREFIX:$module:$code#f$index"
    fun string(module: Int, code: Int, index: Int) = "$PREFIX:$module:$code#s$index"

    /** The parts of an FYT key, or null when [key] isn't one. */
    fun parse(key: String): Parsed? {
        val m = KEY.matchEntire(key) ?: return null
        val kind = m.groupValues[4].ifEmpty { "i" }[0]
        return Parsed(m.groupValues[1].toInt(), m.groupValues[2].toInt(), kind, m.groupValues[5].toInt(), m.groupValues[3].toIntOrNull())
    }

    /** Every finder key one update produces, with its value as text. */
    fun values(module: Int, code: Int, ints: IntArray?, flts: FloatArray?, strs: Array<String?>?): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        ints?.forEachIndexed { i, v -> out[int(module, code, i)] = v.toString() }
        val at = MUX[module]?.get(code)
        val sub = if (at != null) ints?.getOrNull(at) else null
        if (sub != null) ints?.forEachIndexed { i, v -> if (i != at) out[subInt(module, code, sub, i)] = v.toString() }
        flts?.forEachIndexed { i, v -> out[float(module, code, i)] = v.toString() }
        strs?.forEachIndexed { i, v -> if (v != null) out[string(module, code, i)] = v }
        return out
    }

    private val KEY = Regex("^$PREFIX:(\\d+):(\\d+)(?:\\[(\\d+)])?#([fs]?)(\\d+)$")
}

/**
 * Module CANBUS's per-car codes on a Volkswagen, as a tester logged them on a
 * Sharan 7N (Xtrons IMH94MTVP, 2026-11): the CAN decoder's own codes, so not
 * every VW decoder may use them. Read only on a car set up as a VW (or its
 * Seat twin), and each value only within a plausible range. The seatbelt
 * (101), the fuel level (104, unit unknown) and the wheel keys (124) are
 * logged too but not decoded yet: their values aren't known.
 */
object FytVw {
    const val U_DRIVER_BELT = 101
    /** ints[0]: 0 applied, 1 released. */
    const val U_HANDBRAKE = 103
    const val U_FUEL_LEVEL = 104
    /** ints[0]: the on-board voltage × 100. */
    const val U_VOLTS = 105
    /** ints[0]: the total mileage, km assumed. */
    const val U_ODOMETER = 106
    /** ints[0]: the speed × 100, km/h. */
    const val U_SPEED = 109
    /** ints[0]: engine revolutions per minute. */
    const val U_ENGINE_SPEED = 110
    /** ints[0]: the outside temperature × 10, °C. */
    const val U_OUTSIDE_TEMP = 121
    const val U_WHEEL_KEYS = 124

    /** Every code logged, subscribed to on every FYT unit so the Signal Finder shows them whatever the car. */
    val CODES = intArrayOf(U_DRIVER_BELT, U_HANDBRAKE, U_FUEL_LEVEL, U_VOLTS, U_ODOMETER, U_SPEED, U_ENGINE_SPEED, U_OUTSIDE_TEMP, U_WHEEL_KEYS)

    /** The makes whose decoders are taken to send these codes. */
    private val MAKES = setOf("vw", "volkswagen", "seat")

    fun isVw(make: String): Boolean = make.trim().lowercase() in MAKES

    fun handbrake(ints: IntArray?): Boolean? = when (ints?.firstOrNull()) { 0 -> true; 1 -> false; else -> null }

    fun volts(ints: IntArray?): Double? = ints?.firstOrNull()?.div(100.0)?.takeIf { it in 6.0..18.0 }

    fun odometerKm(ints: IntArray?): Double? = ints?.firstOrNull()?.takeIf { it in 1..2_000_000 }?.toDouble()

    fun speedKmh(ints: IntArray?): Float? = ints?.firstOrNull()?.div(100f)?.takeIf { it in 0f..400f }

    fun rpm(ints: IntArray?): Int? = ints?.firstOrNull()?.takeIf { it in 0..12_000 }

    fun outsideTemp(ints: IntArray?): Double? = ints?.firstOrNull()?.div(10.0)?.takeIf { it in -50.0..70.0 }
}
