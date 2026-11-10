package com.openauto.dash.carphoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO

/**
 * The car from a 3D model: a box car built here (wheels named, nose +z as most
 * downloads), and any .glb put in build/carmodel-in (not kept in the repo),
 * whose pictures land in build/carmodel-out for a look.
 */
class CarModelKitTest {

    private val out = File("build/carmodel-out").apply { mkdirs() }

    private fun decode(bytes: ByteArray): Argb? {
        val img = ImageIO.read(ByteArrayInputStream(bytes)) ?: return null
        val px = IntArray(img.width * img.height)
        img.getRGB(0, 0, img.width, img.height, px, 0, img.width)
        return Argb(img.width, img.height, px)
    }

    private fun save(a: Argb, name: String) {
        val img = BufferedImage(a.w, a.h, BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, a.w, a.h, a.px, 0, a.w)
        ImageIO.write(img, "png", File(out, name))
    }

    /**
     * Boxes as a .glb, car space (x nose, y left, z up) written glTF style: x = left, y = up, z = nose.
     * [rigged]: as tools/mycar/bake_car.py rigs a car instead (x = nose, y = up, z = right), with a
     * bonnet hanging from a Hinge_bonnet node at its back edge. [zUp]: as Blender saves it
     * unless told otherwise: x = right, y = nose, z = up.
     */
    private fun boxCar(rigged: Boolean = false, zUp: Boolean = false): ByteArray {
        class Box(val name: String, val lo: FloatArray, val hi: FloatArray, val mat: Int)
        val boxes = listOf(
            Box("Body", floatArrayOf(-2.2f, -0.9f, 0.3f), floatArrayOf(2.2f, 0.9f, 1.0f), 0),
            Box("Cabin", floatArrayOf(-1.6f, -0.8f, 1.0f), floatArrayOf(0.9f, 0.8f, 1.6f), 0),
            Box("Wheel_FL", floatArrayOf(1.1f, 0.6f, 0f), floatArrayOf(1.7f, 0.9f, 0.62f), 1),
            Box("Wheel_FR", floatArrayOf(1.1f, -0.9f, 0f), floatArrayOf(1.7f, -0.6f, 0.62f), 1),
            Box("Wheel_RL", floatArrayOf(-1.7f, 0.6f, 0f), floatArrayOf(-1.1f, 0.9f, 0.62f), 1),
            Box("Wheel_RR", floatArrayOf(-1.7f, -0.9f, 0f), floatArrayOf(-1.1f, -0.6f, 0.62f), 1),
            Box("Tail_light_L", floatArrayOf(-2.25f, 0.5f, 0.8f), floatArrayOf(-2.2f, 0.8f, 0.95f), 2),
            Box("Tail_light_R", floatArrayOf(-2.25f, -0.8f, 0.8f), floatArrayOf(-2.2f, -0.5f, 0.95f), 2),
            Box("Ground", floatArrayOf(-6f, -6f, 0f), floatArrayOf(6f, 6f, 0f), 3)
        ) + if (rigged) listOf(Box("Bonnet", floatArrayOf(1.0f, -0.85f, 1.0f), floatArrayOf(2.2f, 0.85f, 1.06f), 0)) else emptyList()
        // The bonnet's hinge: the middle of its back edge (glTF: x = nose, y = up, z = right).
        val hinge = floatArrayOf(1.0f, 1.06f, 0f)
        val bin = ByteBuffer.allocate(boxes.size * (8 * 12 + 36 * 2)).order(ByteOrder.LITTLE_ENDIAN)
        val faces = intArrayOf(0, 1, 3, 0, 3, 2, 4, 6, 7, 4, 7, 5, 0, 4, 5, 0, 5, 1, 2, 3, 7, 2, 7, 6, 0, 2, 6, 0, 6, 4, 1, 5, 7, 1, 7, 3)
        val json = StringBuilder()
        val meshes = ArrayList<String>(); val nodes = ArrayList<String>(); val accessors = ArrayList<String>(); val views = ArrayList<String>()
        for ((i, b) in boxes.withIndex()) {
            val start = bin.position()
            for (c in 0 until 8) {
                val x = if (c and 4 != 0) b.hi[0] else b.lo[0]
                val y = if (c and 2 != 0) b.hi[1] else b.lo[1]
                val z = if (c and 1 != 0) b.hi[2] else b.lo[2]
                when {
                    rigged -> bin.putFloat(x).putFloat(z).putFloat(-y)
                    zUp -> bin.putFloat(-y).putFloat(x).putFloat(z)
                    else -> bin.putFloat(y).putFloat(z).putFloat(x)
                }
            }
            val idxStart = bin.position()
            for (f in faces) bin.putShort(f.toShort())
            views += """{"buffer":0,"byteOffset":$start,"byteLength":96}"""
            views += """{"buffer":0,"byteOffset":$idxStart,"byteLength":72}"""
            accessors += """{"bufferView":${i * 2},"componentType":5126,"count":8,"type":"VEC3"}"""
            accessors += """{"bufferView":${i * 2 + 1},"componentType":5123,"count":36,"type":"SCALAR"}"""
            meshes += """{"name":"${b.name}","primitives":[{"attributes":{"POSITION":${i * 2}},"indices":${i * 2 + 1},"material":${b.mat}}]}"""
            // The bonnet's own node sits back where it was under its hinge.
            nodes += if (rigged && b.name == "Bonnet") """{"name":"${b.name}","mesh":$i,"translation":[${-hinge[0]},${-hinge[1]},${-hinge[2]}]}"""
            else """{"name":"${b.name}","mesh":$i}"""
        }
        val roots = boxes.indices.filter { !(rigged && boxes[it].name == "Bonnet") }.toMutableList()
        if (rigged) {
            nodes += """{"name":"Hinge_bonnet","translation":[${hinge[0]},${hinge[1]},${hinge[2]}],"children":[${boxes.indexOfFirst { it.name == "Bonnet" }}]}"""
            roots += nodes.size - 1
        }
        json.append("""{"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[${roots.joinToString()}]}],""")
        json.append(""""nodes":[${nodes.joinToString()}],"meshes":[${meshes.joinToString()}],"accessors":[${accessors.joinToString()}],""")
        json.append(""""bufferViews":[${views.joinToString()}],"buffers":[{"byteLength":${bin.position()}}],""")
        json.append(""""materials":[{"name":"paint","pbrMetallicRoughness":{"baseColorFactor":[0.5,0.05,0.04,1],"metallicFactor":0.6,"roughnessFactor":0.3}},""")
        json.append("""{"name":"tyre","pbrMetallicRoughness":{"baseColorFactor":[0.02,0.02,0.02,1],"metallicFactor":0,"roughnessFactor":0.9}},""")
        json.append("""{"name":"tail light","pbrMetallicRoughness":{"baseColorFactor":[0.8,0,0,1],"metallicFactor":0}},""")
        json.append("""{"name":"floor","pbrMetallicRoughness":{"baseColorFactor":[0.3,0.3,0.3,1],"metallicFactor":0}}]}""")
        return Glb.write(kotlinx.serialization.json.Json.parseToJsonElement(json.toString()) as kotlinx.serialization.json.JsonObject, bin.array().copyOf(bin.position()))
    }

    @Test
    fun aBoxCarFacingPlusZIsBakedNoseRightAndUp() {
        val model = CarModelKit.read(boxCar(), ::decode)
        // The ground plane is left out of the size.
        assertEquals(4.45f, model.length, 0.01f)
        assertEquals(1.8f, model.width, 0.01f)
        assertEquals(1.6f, model.height, 0.01f)
        val r = CarModelKit.bake(model, "Box")
        save(r.side, "box_side.png"); save(r.top, "box_top.png"); save(r.hero, "box_hero.png"); save(r.back, "box_back.png")
        assertTrue("wheels from their names", r.wheelsNamed)
        assertTrue("nose to the right", r.sideNose.x > r.sideTail.x)
        assertTrue("nose up", r.topView.nose.y < r.topView.tail.y)
        // Front wheel nearer the nose than the rear one, both low.
        assertTrue(r.sideWheels[0].x > r.sideWheels[1].x)
        assertTrue(r.sideWheels.all { it.y > 0.5f })
        // From above: front left on the picture's left (the car's left), front ones nearer the top.
        val (fl, fr, rl, _) = r.topView.wheels
        assertTrue(fl.x < fr.x)
        assertTrue(fl.y < rl.y)
        // The tail lamps found, the car's left on the picture's left from behind.
        assertEquals(2, r.backLights.size)
        assertTrue(r.backLights[0].x < r.backLights[1].x)
        // Something drawn in the middle of the side picture, nothing in its corner.
        assertTrue((r.side.px[r.side.h / 2 * r.side.w + r.side.w / 2] ushr 24) > 200)
        assertEquals(0, r.side.px[0] ushr 24)
        assertTrue(r.toCarJson().contains("\"model\":\"model.glb\""))
    }

    @Test
    fun theLiveCopyIsTurnedNosePlusX() {
        val model = CarModelKit.read(boxCar(), ::decode)
        val live = model.glbForLive()
        // Read again as it is: now the length lies along x, nose at +x.
        val again = CarModelKit.read(live, ::decode)
        assertEquals(4.45f, again.length, 0.01f)
        val doc = Glb.parse(live)
        assertNotNull(doc.json["nodes"])
        val r = CarModelKit.bake(again, withLive = false)
        assertTrue("still nose right", r.sideWheels[0].x > r.sideWheels[1].x)
    }

    @Test
    fun turnedReadsTheNoseTheOtherWay() {
        val r = CarModelKit.bake(CarModelKit.read(boxCar(), ::decode, turned = true), withLive = false)
        // The model's +z end is now the tail: the lamps named tail lights sit at the front, none at the back.
        assertTrue(r.backLights.isEmpty())
    }

    @Test
    fun aModelSavedZUpStandsOnItsWheels() {
        val model = CarModelKit.read(boxCar(zUp = true), ::decode)
        assertEquals(4.45f, model.length, 0.01f)
        assertEquals(1.8f, model.width, 0.01f)
        assertEquals(1.6f, model.height, 0.01f)
        val r = CarModelKit.bake(model, "Z up", withLive = false)
        save(r.top, "zup_top.png"); save(r.side, "zup_side.png")
        // Wheels low on the side picture, the cabin up: the same car as saved y up.
        assertTrue(r.sideWheels.all { it.y > 0.5f })
        assertTrue("nose up", r.topView.nose.y < r.topView.tail.y)
        val (fl, fr, rl, _) = r.topView.wheels
        assertTrue(fl.x < fr.x)
        assertTrue(fl.y < rl.y)
        assertEquals(2, r.backLights.size)
        assertTrue(r.backLights[0].x < r.backLights[1].x)
    }

    @Test
    fun aRiggedBonnetOpensFromAbove() {
        val model = CarModelKit.read(boxCar(rigged = true), ::decode)
        // Nose +x as rigged: the same car, its bonnet on its hinge.
        assertEquals(4.45f, model.length, 0.01f)
        assertEquals(listOf("Hinge_bonnet"), model.hinges.map { it.name })
        assertEquals(CarPart.BONNET, model.hinges[0].part)
        val before = model.tris.pos.copyOf()
        val r = CarModelKit.bake(model, "Rigged", withLive = false)
        // Put back as it was read.
        assertTrue(same(before, model.tris.pos))
        val bonnet = r.openings.single()
        assertEquals(CarPart.BONNET, bonnet.part)
        assertEquals(CarModelKit.OPEN_FRAMES, bonnet.frames.size)
        bonnet.frames.forEachIndexed { i, f -> save(f.image, "rigged_bonnet_$i.png") }
        save(bonnet.under.image, "rigged_bonnet_under.png")
        // From above, the bonnet rising on its back edge gets shorter, toward the windscreen.
        val first = bonnet.frames.first().box
        val last = bonnet.frames.last().box
        assertTrue("shorter open: $first $last", last[3] - last[1] < (first[3] - first[1]) * 0.85f)
        assertEquals(first[3], last[3], 0.02f)
        // Near the nose: the upper part of the picture.
        assertTrue(last[3] < 0.5f)
        // What it uncovers spans everywhere it goes, and something is drawn in each frame.
        assertTrue(bonnet.under.box[1] <= first[1] && bonnet.under.box[3] >= first[3])
        assertTrue(bonnet.frames.all { f -> f.image.px.any { (it ushr 24) > 200 } })
        // In car.json, with its files.
        val json = r.toCarJson()
        assertTrue(json.contains("\"openings\""))
        assertEquals(1 + CarModelKit.OPEN_FRAMES, r.openingEntries().size)
        assertTrue(r.openingEntries().keys.all { json.contains(it) })
    }

    @Test
    fun aModelWithoutHingesOpensNothing() {
        val r = CarModelKit.bake(CarModelKit.read(boxCar(), ::decode), withLive = false)
        assertTrue(r.openings.isEmpty())
        assertTrue(!r.toCarJson().contains("openings"))
    }

    @Test
    fun aTurnedRigKeepsItsHingeOnTheCar() {
        val model = CarModelKit.read(boxCar(rigged = true), ::decode)
        val turned = model.turnedAround()
        val a = model.hinges[0]; val b = turned.hinges[0]
        assertEquals(-a.pivot[0], b.pivot[0], 1e-5f)
        assertEquals(-a.axis[1], b.axis[1], 1e-5f)
        assertEquals(a.pivot[2], b.pivot[2], 1e-5f)
    }

    @Test
    fun turningAroundIsReadingTurned() {
        val read = CarModelKit.read(boxCar(), ::decode, turned = true)
        val turned = CarModelKit.read(boxCar(), ::decode).turnedAround()
        assertTrue(same(read.tris.pos, turned.tris.pos))
        assertTrue(same(read.tris.nrm, turned.tris.nrm))
        assertTrue(read.glbForLive().contentEquals(turned.glbForLive()))
        // And back again.
        assertTrue(same(CarModelKit.read(boxCar(), ::decode).tris.pos, turned.turnedAround().tris.pos))
    }

    /** Equal as numbers (0 and -0 alike). */
    private fun same(a: FloatArray, b: FloatArray) = a.size == b.size && a.indices.all { a[it] == b[it] }

    /** [a] with a green cross at each point, to see the anchors. */
    private fun marked(a: Argb, points: List<CarPoint>): Argb {
        val o = Argb(a.w, a.h, a.px.copyOf())
        for (p in points) {
            val cx = (p.x * a.w).toInt(); val cy = (p.y * a.h).toInt()
            for (d in -12..12) for (t in -1..1) {
                listOf(cx + d to cy + t, cx + t to cy + d).forEach { (x, y) -> if (x in 0 until a.w && y in 0 until a.h) o.px[y * a.w + x] = 0xFF00FF00.toInt() }
            }
        }
        return o
    }

    /** Any model put in build/carmodel-in, baked for a look. */
    @Test
    fun modelsGivenAreBaked() {
        val dir = File("build/carmodel-in")
        val files = dir.listFiles { f -> f.extension.equals("glb", true) }.orEmpty()
        assumeTrue("no models in $dir", files.isNotEmpty())
        for (f in files) {
            val t0 = System.nanoTime()
            var model = CarModelKit.read(f.readBytes(), ::decode)
            val backwards = CarModelKit.noseLooksBackwards(model)
            if (backwards) model = model.turnedAround()
            val r = CarModelKit.bake(model, f.nameWithoutExtension)
            val ms = (System.nanoTime() - t0) / 1_000_000
            val tag = f.nameWithoutExtension.replace(Regex("[^A-Za-z0-9]+"), "_")
            for ((name, pic) in r.packEntries()) save(pic, "${tag}_$name")
            save(marked(r.side, r.sideWheels + r.sideNose + r.sideTail), "${tag}_marks_side.png")
            save(marked(r.top, r.topView.wheels + r.topView.nose + r.topView.tail), "${tag}_marks_top.png")
            if ("PICASSO" in f.name.uppercase()) {
                // The C4's own render from above with three parts open (the photo tests' picture).
                val img = javaClass.getResourceAsStream("/carphoto/top_open.png")!!.use { ImageIO.read(it) }
                val opened = Argb(img.width, img.height, IntArray(img.width * img.height).also { img.getRGB(0, 0, img.width, img.height, it, 0, img.width) })
                val parts = CarPhotoKit.openParts(opened, r.topView)
                val o = Argb(r.top.w, r.top.h, r.top.px.copyOf())
                for (p in parts) {
                    val x0 = (p.box[0] * r.top.w).toInt(); val y0 = (p.box[1] * r.top.h).toInt()
                    val pw = ((p.box[2] - p.box[0]) * r.top.w).toInt().coerceAtLeast(1); val ph = ((p.box[3] - p.box[1]) * r.top.h).toInt().coerceAtLeast(1)
                    for (y in 0 until ph) for (x in 0 until pw) {
                        val sx = x * p.image.w / pw; val sy = y * p.image.h / ph
                        val c = p.image.px[sy * p.image.w + sx]
                        val tx = x0 + x; val ty = y0 + y
                        if ((c ushr 24) > 128 && tx in 0 until o.w && ty in 0 until o.h) o.px[ty * o.w + tx] = c
                    }
                }
                save(o, "${tag}_doors.png")
                println("$tag doors: ${parts.map { it.part }}")
            }
            println("$tag: ${model.triangles} triangles, ${"%.2f".format(model.length)} x ${"%.2f".format(model.width)} x ${"%.2f".format(model.height)}, turned $backwards, wheels named ${r.wheelsNamed}, lights ${r.backLights.size}/${r.frontLights.size}, live ${r.model?.size}, $ms ms")
        }
    }
}
