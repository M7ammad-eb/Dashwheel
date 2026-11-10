package com.openauto.dash.carphoto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO

/**
 * The live 3D copy of a model ([CarLiveModel]): lamps and wheels found and
 * named, heavy models made lighter. Any .glb in build/carmodel-in (not kept
 * in the repo) is made into build/carmodel-out/<name>.live.glb for a look.
 */
class CarLiveModelTest {

    private fun decode(bytes: ByteArray): Argb? {
        val img = ImageIO.read(ByteArrayInputStream(bytes)) ?: return null
        val px = IntArray(img.width * img.height)
        img.getRGB(0, 0, img.width, img.height, px, 0, img.width)
        return Argb(img.width, img.height, px)
    }

    private class Box(val name: String, val lo: FloatArray, val hi: FloatArray, val mat: Int)

    /**
     * Boxes as a .glb (car space x nose, y left, z up, written glTF style: x =
     * left, y = up, z = nose, as most downloads face), each box cut into
     * [split]^2 squares a face so a model can be made heavy.
     */
    private fun glb(boxes: List<Box>, materials: String, split: Int = 1): ByteArray {
        val bin = java.io.ByteArrayOutputStream()
        val meshes = ArrayList<String>(); val nodes = ArrayList<String>(); val accessors = ArrayList<String>(); val views = ArrayList<String>()
        for ((i, b) in boxes.withIndex()) {
            val pos = ArrayList<Float>()
            // Each of the six faces as a split x split grid of two-triangle squares.
            for (axis in 0..2) for (side in 0..1) {
                val u = (axis + 1) % 3; val v = (axis + 2) % 3
                for (a in 0 until split) for (c in 0 until split) {
                    fun corner(du: Int, dv: Int): FloatArray {
                        val p = FloatArray(3)
                        p[axis] = if (side == 0) b.lo[axis] else b.hi[axis]
                        p[u] = b.lo[u] + (b.hi[u] - b.lo[u]) * (a + du) / split
                        p[v] = b.lo[v] + (b.hi[v] - b.lo[v]) * (c + dv) / split
                        return p
                    }
                    for (p in listOf(corner(0, 0), corner(1, 0), corner(1, 1), corner(0, 0), corner(1, 1), corner(0, 1))) {
                        pos += p[1]; pos += p[2]; pos += p[0]
                    }
                }
            }
            val bb = ByteBuffer.allocate(pos.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (f in pos) bb.putFloat(f)
            val start = bin.size()
            bin.write(bb.array())
            views += """{"buffer":0,"byteOffset":$start,"byteLength":${pos.size * 4}}"""
            val xs = pos.filterIndexed { k, _ -> k % 3 == 0 }; val ys = pos.filterIndexed { k, _ -> k % 3 == 1 }; val zs = pos.filterIndexed { k, _ -> k % 3 == 2 }
            accessors += """{"bufferView":$i,"componentType":5126,"count":${pos.size / 3},"type":"VEC3","min":[${xs.min()},${ys.min()},${zs.min()}],"max":[${xs.max()},${ys.max()},${zs.max()}]}"""
            meshes += """{"name":"${b.name}","primitives":[{"attributes":{"POSITION":$i},"material":${b.mat}}]}"""
            nodes += """{"name":"${b.name}","mesh":$i}"""
        }
        val json = """{"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[${boxes.indices.joinToString()}]}],""" +
            """"nodes":[${nodes.joinToString()}],"meshes":[${meshes.joinToString()}],"accessors":[${accessors.joinToString()}],""" +
            """"bufferViews":[${views.joinToString()}],"buffers":[{"byteLength":${bin.size()}}],"materials":[$materials]}"""
        return Glb.write(Json.parseToJsonElement(json) as JsonObject, bin.toByteArray())
    }

    /** A grey car whose parts have no useful names: the lamps only show by where they are and their colour. */
    private fun anonymousCar(split: Int = 1) = glb(
        listOf(
            Box("Object_1", floatArrayOf(-2.2f, -0.9f, 0.3f), floatArrayOf(2.2f, 0.9f, 1.0f), 0),
            Box("Object_2", floatArrayOf(-1.6f, -0.8f, 1.0f), floatArrayOf(0.9f, 0.8f, 1.6f), 0),
            Box("Object_3", floatArrayOf(1.1f, 0.6f, 0f), floatArrayOf(1.7f, 0.9f, 0.62f), 1),
            Box("Object_4", floatArrayOf(1.1f, -0.9f, 0f), floatArrayOf(1.7f, -0.6f, 0.62f), 1),
            Box("Object_5", floatArrayOf(-1.7f, 0.6f, 0f), floatArrayOf(-1.1f, 0.9f, 0.62f), 1),
            Box("Object_6", floatArrayOf(-1.7f, -0.9f, 0f), floatArrayOf(-1.1f, -0.6f, 0.62f), 1),
            Box("Object_7", floatArrayOf(-2.25f, 0.5f, 0.8f), floatArrayOf(-2.2f, 0.8f, 0.95f), 2),
            Box("Object_8", floatArrayOf(-2.25f, -0.8f, 0.8f), floatArrayOf(-2.2f, -0.5f, 0.95f), 2),
            Box("Object_9", floatArrayOf(2.2f, 0.45f, 0.7f), floatArrayOf(2.25f, 0.8f, 0.85f), 3),
            Box("Object_10", floatArrayOf(2.2f, -0.8f, 0.7f), floatArrayOf(2.25f, -0.45f, 0.85f), 3)
        ),
        """{"name":"Material.001","pbrMetallicRoughness":{"baseColorFactor":[0.5,0.52,0.55,1],"metallicFactor":0.8,"roughnessFactor":0.3}},""" +
            """{"name":"Material.002","pbrMetallicRoughness":{"baseColorFactor":[0.02,0.02,0.02,1],"metallicFactor":0,"roughnessFactor":0.9}},""" +
            """{"name":"Material.003","pbrMetallicRoughness":{"baseColorFactor":[0.7,0.02,0.02,1],"metallicFactor":0,"roughnessFactor":0.2}},""" +
            """{"name":"Material.004","pbrMetallicRoughness":{"baseColorFactor":[0.9,0.9,0.92,0.4],"metallicFactor":0,"roughnessFactor":0.05},"alphaMode":"BLEND"}""",
        split
    )

    /** Each node's name and the middle of its positions (glTF space). */
    private fun nodes(glb: ByteArray): Map<String, FloatArray> {
        val doc = Glb.parse(glb)
        val j = doc.json
        val acc = j["accessors"]!!.jsonArray
        return j["nodes"]!!.jsonArray.mapNotNull { n ->
            val o = n.jsonObject
            val mesh = o["mesh"]?.jsonPrimitive?.int ?: return@mapNotNull null
            val prims = j["meshes"]!!.jsonArray[mesh].jsonObject["primitives"]!!.jsonArray
            val mid = FloatArray(3)
            for (p in prims) {
                val a = acc[p.jsonObject["attributes"]!!.jsonObject["POSITION"]!!.jsonPrimitive.int].jsonObject
                val lo = a["min"]!!.jsonArray.map { it.jsonPrimitive.float }; val hi = a["max"]!!.jsonArray.map { it.jsonPrimitive.float }
                for (k in 0..2) mid[k] += (lo[k] + hi[k]) / 2 / prims.size
            }
            o["name"]!!.jsonPrimitive.content to mid
        }.toMap()
    }

    @Test
    fun lampsAndWheelsAreFoundAndNamedWithoutNamesInTheModel() {
        val model = CarModelKit.read(anonymousCar(), ::decode)
        val live = CarModelKit.bake(model, "Box").model
        assertNotNull(live)
        val n = nodes(live!!)
        for (name in listOf("Lamp_tail_l", "Lamp_tail_r", "Lamp_head_l", "Lamp_head_r", "Wheel_fl", "Wheel_fr", "Wheel_rl", "Wheel_rr", "Body")) {
            assertTrue("$name in ${n.keys}", name in n)
        }
        // glTF: nose +x, the car's left at -z.
        assertTrue(n["Lamp_tail_l"]!![0] < 0f && n["Lamp_tail_l"]!![2] < 0f)
        assertTrue(n["Lamp_tail_r"]!![0] < 0f && n["Lamp_tail_r"]!![2] > 0f)
        assertTrue(n["Lamp_head_l"]!![0] > 0f && n["Lamp_head_l"]!![2] < 0f)
        assertTrue(n["Wheel_fr"]!![0] > 0f && n["Wheel_fr"]!![2] > 0f)
        assertTrue(n["Wheel_rl"]!![0] < 0f && n["Wheel_rl"]!![2] < 0f)
    }

    @Test
    fun aHeavyModelIsMadeLighterKeepingItsLamps() {
        val model = CarModelKit.read(anonymousCar(split = 40), ::decode)
        assertTrue("heavy: ${model.triangles}", model.triangles > 100_000)
        val wheels = listOf(floatArrayOf(1.4f, 0.75f, 0.31f), floatArrayOf(1.4f, -0.75f, 0.31f), floatArrayOf(-1.4f, 0.75f, 0.31f), floatArrayOf(-1.4f, -0.75f, 0.31f))
        val group = CarLiveModel.groups(model, wheels)
        val mesh = CarLiveModel.simplify(model.tris, group, 20_000)
        assertTrue("light: ${mesh.triangles}", mesh.triangles in 2_000..20_000)
        val live = CarLiveModel.build(model, wheels, maxTriangles = 20_000)
        assertNotNull(live)
        assertTrue("Lamp_tail_l" in nodes(live!!))
    }

    @Test
    fun aModelAlreadyLightKeepsEveryTriangle() {
        val model = CarModelKit.read(anonymousCar(), ::decode)
        val group = CarLiveModel.groups(model, emptyList())
        assertEquals(model.triangles, CarLiveModel.simplify(model.tris, group, 120_000).triangles)
    }

    @Test
    fun pngIsReadBack() {
        val a = Argb(3, 2, intArrayOf(0xffff0000.toInt(), 0xff00ff00.toInt(), 0x800000ff.toInt(), 0, -1, 0xff123456.toInt()))
        val img = ImageIO.read(ByteArrayInputStream(CarLiveModel.png(a)))
        assertEquals(3, img.width)
        assertEquals(0xffff0000.toInt(), img.getRGB(0, 0))
        assertEquals(0xff123456.toInt(), img.getRGB(2, 1))
    }

    @Test
    fun modelsOnDiskAreMadeLive() {
        val dir = File("build/carmodel-in")
        val files = dir.listFiles { f -> f.name.endsWith(".glb") }.orEmpty()
        assumeTrue("no models in $dir", files.isNotEmpty())
        val out = File("build/carmodel-out").apply { mkdirs() }
        for (f in files) {
            // As the phone does: turned around when the nose looks backwards.
            var model = CarModelKit.read(f.readBytes(), ::decode)
            if (CarModelKit.noseLooksBackwards(model)) model = model.turnedAround()
            val live = CarModelKit.bake(model, f.nameWithoutExtension).model ?: continue
            File(out, f.nameWithoutExtension + ".live.glb").writeBytes(live)
            println("${f.name}: ${model.triangles} triangles -> ${live.size / 1024} KB, nodes ${nodes(live).keys}")
        }
    }
}
