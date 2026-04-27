package com.margelo.nitro.lutpro

import java.io.File
import kotlin.text.Charsets

data class CubeLutData(
  val size: Int,
  val domainMin: FloatArray = floatArrayOf(0f, 0f, 0f),
  val domainMax: FloatArray = floatArrayOf(1f, 1f, 1f),
  /** R-fastest, layout: offset = (r + g*size + b*size*size) * 4, RGBA8 */
  val rgba8: ByteArray,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false
    other as CubeLutData
    if (size != other.size) return false
    if (!domainMin.contentEquals(other.domainMin)) return false
    if (!domainMax.contentEquals(other.domainMax)) return false
    if (!rgba8.contentEquals(other.rgba8)) return false
    return true
  }

  override fun hashCode(): Int {
    var result = size
    result = 31 * result + domainMin.contentHashCode()
    result = 31 * result + domainMax.contentHashCode()
    result = 31 * result + rgba8.contentHashCode()
    return result
  }
}

object CubeLutParser {
  fun parseFile(path: String): CubeLutData {
    val text = File(path).readText(Charsets.UTF_8)
    return parse(text)
  }

  fun parse(text: String): CubeLutData {
    var size = 0
    var domainMin = floatArrayOf(0f, 0f, 0f)
    var domainMax = floatArrayOf(1f, 1f, 1f)
    val dataLines = ArrayList<String>(4096)
    for (raw in text.split('\n', '\r')) {
      val line = raw.trim()
      if (line.isEmpty() || line.startsWith("#")) continue
      if (line.length >= 5 && line.substring(0, 5).equals("TITLE", ignoreCase = true)) continue
      if (line.length >= 12 && line.substring(0, 12).equals("LUT_3D_SIZE", ignoreCase = true)) {
        val n = line.split(Regex("\\s+")).lastOrNull()?.toIntOrNull()
        requireNotNull(n) { "Invalid LUT_3D_SIZE" }
        require(n in 2..256) { "Invalid LUT_3D_SIZE: $n" }
        size = n
        continue
      }
      if (line.length >= 9 && line.substring(0, 9).equals("DOMAIN_MIN", ignoreCase = true)) {
        domainMin = parseThreeFloats(line, "DOMAIN_MIN")
        continue
      }
      if (line.length >= 9 && line.substring(0, 9).equals("DOMAIN_MAX", ignoreCase = true)) {
        domainMax = parseThreeFloats(line, "DOMAIN_MAX")
        continue
      }
      if (size > 0) {
        dataLines.add(line)
      }
    }
    require(size > 0) { "Missing LUT_3D_SIZE in .cube" }
    val expected = size * size * size
    require(dataLines.size == expected) {
      "Expected $expected RGB lines in .cube, got ${dataLines.size}"
    }
    val rgba = ByteArray(expected * 4)
    val n = size
    for (i in 0 until expected) {
      val rgb = parseRgbLine(dataLines[i])
      val b = i % n
      val g = (i / n) % n
      val r = i / (n * n)
      val mem = (r + g * n + b * n * n) * 4
      val cr = rgb[0].coerceIn(0f, 1f)
      val cg = rgb[1].coerceIn(0f, 1f)
      val cb = rgb[2].coerceIn(0f, 1f)
      rgba[mem] = (cr * 255f).toInt().coerceIn(0, 255).toByte()
      rgba[mem + 1] = (cg * 255f).toInt().coerceIn(0, 255).toByte()
      rgba[mem + 2] = (cb * 255f).toInt().coerceIn(0, 255).toByte()
      rgba[mem + 3] = 255.toByte()
    }
    return CubeLutData(
      size = size,
      domainMin = domainMin,
      domainMax = domainMax,
      rgba8 = rgba,
    )
  }

  private fun parseThreeFloats(
    line: String,
    header: String,
  ): FloatArray {
    val parts = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
    require(parts[0].equals(header, ignoreCase = true)) { line }
    require(parts.size >= 4) { line }
    return floatArrayOf(
      parts[1].toFloat(),
      parts[2].toFloat(),
      parts[3].toFloat(),
    )
  }

  private fun parseRgbLine(line: String): FloatArray {
    val parts = line.split(Regex("\\s+|\t+")).filter { it.isNotEmpty() }
    require(parts.size >= 3) { line }
    return floatArrayOf(
      parts[0].toFloat(),
      parts[1].toFloat(),
      parts[2].toFloat(),
    )
  }
}
