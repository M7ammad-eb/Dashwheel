package com.openauto.dash

/*
 * What the QF firmware's car app (com.qf.vehicle) shares with launchers from
 * the CAN box, decoded ([CarBox] receives it). Worked out from the firmware,
 * not a public API: each message is a byte array, byte 0 = 0x2E, byte 1 = its
 * kind, multi-byte numbers big-endian. A value the car doesn't send reads as
 * all ones (0xFFFF) and comes out null here.
 */

internal const val SHARE_MAGIC = 0x2E
internal const val SHARE_AC = 1
internal const val SHARE_BODY = 2
internal const val SHARE_DOORS = 3
internal const val SHARE_RADAR = 4

/** The car's body and trip computer, as the CAN box reports them (type 2). */
data class CarBody(
    val speedKmh: Int?,
    val rpm: Int?,
    /** Fuel left, as the car counts it (litres on most cars; the unit isn't in the data). */
    val fuelLeft: Float?,
    val restCapacity: Float?,
    val odometer: Float?,
    /** Distance the car says it can still drive. */
    val range: Float?,
    val trip1: Float?,
    val instantConsumption: Float?,
    val highBeam: Boolean,
    val dippedBeam: Boolean,
    val frontFog: Boolean,
    val rearFog: Boolean,
    val turnRight: Boolean,
    val turnLeft: Boolean,
    val hazard: Boolean,
    val sidelights: Boolean,
    /** The belt is NOT buckled (the car app shows "unfastened" for this bit). */
    val driverBeltUnfastened: Boolean,
    val passengerBeltUnfastened: Boolean,
    val ignition: Boolean,
    /** The gear flag; one bit, most likely "in reverse" (unconfirmed on the car). */
    val gearFlag: Boolean,
    val handbrake: Boolean,
    val washerFluid: Boolean
)

/** The climate control as the car reports it (type 1). */
data class Climate(
    val power: Boolean,
    val ac: Boolean,
    val auto: Boolean,
    val recirculation: Boolean,
    val dual: Boolean,
    val frontDefrost: Boolean,
    val rearDefrost: Boolean,
    val acMax: Boolean,
    /** Fan speed, 0..15 as sent (cars use up to 7 or 8). */
    val fan: Int,
    val airUp: Boolean,
    val airFace: Boolean,
    val airDown: Boolean,
    val left: ClimateTemp,
    val right: ClimateTemp,
    /** Seat heating 0..15 as sent, left and right. */
    val seatHeatLeft: Int,
    val seatHeatRight: Int
)

sealed interface ClimateTemp {
    data object Low : ClimateTemp
    data object High : ClimateTemp
    data object None : ClimateTemp
    data class Degrees(val value: Float) : ClimateTemp
}

/**
 * The parking sensors: levels 0 = nothing, 1 = closest, up to 10 = far;
 * null for a sensor the car doesn't have. Front and rear list their six
 * sensors left to right; the sides front to back.
 */
data class Radar(
    val front: List<Int?>,
    val rear: List<Int?>,
    val left: List<Int?>,
    val right: List<Int?>
) {
    private val all: List<Int?> get() = front + rear + left + right
    /** Any sensor seeing something. */
    val active: Boolean get() = all.any { it != null && it in 1..MAX_LEVEL }
    /** Whether the car reports any sensor at all. */
    val present: Boolean get() = all.any { it != null }
    /**
     * Whether the car has a sensor that looks forward or to a side. Without one
     * (rear sensors only), the radar has nothing to say out of reverse (#157).
     */
    val seesAhead: Boolean get() = (front + left + right).any { it != null }
    /** The closest level seen (1 = closest), or null when nothing is near. */
    val closest: Int? get() = all.filterNotNull().filter { it in 1..MAX_LEVEL }.minOrNull()

    companion object {
        const val MAX_LEVEL = 10
    }
}

private fun ByteArray.u8(i: Int): Int? = getOrNull(i)?.toInt()?.and(0xFF)

private fun ByteArray.bit(i: Int, bit: Int): Boolean = ((u8(i) ?: 0) shr bit) and 1 == 1

/** Big-endian unsigned over [n] bytes, null when missing or all ones (not sent). */
private fun ByteArray.uBE(i: Int, n: Int): Int? {
    if (i + n > size) return null
    var v = 0
    for (k in 0 until n) v = (v shl 8) or (this[i + k].toInt() and 0xFF)
    val allOnes = (1 shl (8 * n)) - 1
    return if (v == allOnes) null else v
}

private fun ByteArray.tenths(i: Int, n: Int = 2): Float? = uBE(i, n)?.let { it / 10f }

/** The kind of a shared message, or null when it isn't one. */
internal fun shareType(data: ByteArray?): Int? =
    data?.takeIf { it.size >= 2 && it.u8(0) == SHARE_MAGIC }?.u8(1)

/** The door bits of a type 3 message: 0x80 front left, 0x40 front right, 0x20 rear left, 0x10 rear right, 0x08 tailgate, 0x04 bonnet. */
internal fun parseDoorBits(d: ByteArray): Int? =
    d.takeIf { shareType(it) == SHARE_DOORS && it.size >= 3 }?.u8(2)

internal fun parseCarBody(d: ByteArray): CarBody? {
    if (shareType(d) != SHARE_BODY || d.size < 20) return null
    // Every reading 0 or "not sent": this car's box fills none of it (seen on a
    // C4 Picasso). No data, rather than a car at 0 km with its parking brake off.
    if ((2 until d.size).all { d[it] == 0.toByte() || d[it] == 0xFF.toByte() }) return null
    return CarBody(
        speedKmh = d.uBE(7, 2),
        rpm = d.uBE(9, 2),
        fuelLeft = d.tenths(11),
        restCapacity = d.tenths(13),
        odometer = d.tenths(15, 3),
        range = d.tenths(18),
        trip1 = d.tenths(20),
        instantConsumption = d.tenths(51),
        highBeam = d.bit(2, 7),
        dippedBeam = d.bit(2, 6),
        frontFog = d.bit(2, 5),
        rearFog = d.bit(2, 4),
        turnRight = d.bit(2, 3),
        turnLeft = d.bit(2, 2),
        hazard = d.bit(2, 1),
        sidelights = d.bit(3, 7),
        driverBeltUnfastened = d.bit(5, 3),
        passengerBeltUnfastened = d.bit(5, 2),
        ignition = d.bit(6, 7),
        gearFlag = d.bit(6, 5),
        handbrake = d.bit(6, 4),
        washerFluid = d.bit(6, 3)
    )
}

private fun climateTemp(raw: Int?): ClimateTemp = when (raw) {
    null, 0xFE -> ClimateTemp.None
    0 -> ClimateTemp.Low
    0xFF -> ClimateTemp.High
    else -> ClimateTemp.Degrees(raw / 2f)
}

internal fun parseClimate(d: ByteArray): Climate? {
    if (shareType(d) != SHARE_AC || d.size < 11) return null
    return Climate(
        power = d.bit(2, 7),
        ac = d.bit(2, 6),
        auto = d.bit(2, 5) || d.bit(2, 2),
        recirculation = d.bit(2, 4),
        dual = d.bit(2, 0),
        frontDefrost = d.bit(3, 7),
        rearDefrost = d.bit(3, 6),
        acMax = d.bit(3, 5),
        fan = (d.u8(4) ?: 0) and 0x0F,
        airUp = d.bit(4, 7),
        airFace = d.bit(4, 6),
        airDown = d.bit(4, 5),
        left = climateTemp(d.u8(7)),
        right = climateTemp(d.u8(8)),
        seatHeatLeft = ((d.u8(10) ?: 0) shr 4) and 0x0F,
        seatHeatRight = (d.u8(10) ?: 0) and 0x0F
    )
}

/** A level byte: 0xFF (-1) is a sensor the car doesn't have. */
private fun level(raw: Int?): Int? = raw?.takeIf { it != 0xFF }

internal fun parseRadar(d: ByteArray): Radar? {
    if (shareType(d) != SHARE_RADAR || d.size < 38) return null
    return Radar(
        front = (2..7).map { level(d.u8(it)) },
        rear = (8..13).map { level(d.u8(it)) },
        left = (26..29).map { level(d.u8(it)) },
        right = (34..37).map { level(d.u8(it)) }
    )
}

/** The lights worth naming on the Car status tile, most telling first. */
enum class CarLight { HAZARD, MAIN_BEAM, DIPPED, SIDELIGHTS, FRONT_FOG, REAR_FOG }

/**
 * The lights that are on, as the driver would say it: the hazards, then the
 * strongest of main beam / dipped / sidelights (each includes the ones
 * below), then the fogs. Empty when all are off.
 */
internal fun lightsOn(b: CarBody): List<CarLight> = buildList {
    if (b.hazard) add(CarLight.HAZARD)
    when {
        b.highBeam -> add(CarLight.MAIN_BEAM)
        b.dippedBeam -> add(CarLight.DIPPED)
        b.sidelights -> add(CarLight.SIDELIGHTS)
    }
    if (b.frontFog) add(CarLight.FRONT_FOG)
    if (b.rearFog) add(CarLight.REAR_FOG)
}
