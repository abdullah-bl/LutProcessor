@file:Suppress("ktlint:standard:max-line-length", "ktlint:standard:argument-wrapping")

package com.margelo.nitro.lutpro

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.EGL14
import android.opengl.GLES30
import android.opengl.GLUtils
import com.margelo.nitro.NitroModules
import com.margelo.nitro.core.Promise
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.egl.EGLSurface

/**
 * Hald CLUT (PNG) or Iridas/Adobe .cube 3D LUT via OpenGL ES 3.
 */
class HybridLutProcessor : HybridLutProcessorSpec() {
  override fun applyLut(
    imagePath: String,
    lutPath: String,
    intensity: Double,
  ): Promise<String> {
    return Promise.parallel {
      val f = intensity.toFloat().coerceIn(0f, 1f)
      EglHaldLutRenderer.applyLut(
        imagePath = normalizeFilePath(imagePath),
        lutPath = normalizeFilePath(lutPath),
        intensity = f,
      )
    }
  }
}

private fun normalizeFilePath(path: String): String {
  return if (path.startsWith("file://")) {
    path.removePrefix("file://")
  } else {
    path
  }
}

private object EglHaldLutRenderer {
  private const val VERT = """#version 300 es
const vec2 kPos[3] = vec2[3](
  vec2(-1.0, -1.0), vec2(3.0, -1.0), vec2(-1.0, 3.0)
);
const vec2 kUv[3] = vec2[3](
  vec2(0.0, 0.0), vec2(2.0, 0.0), vec2(0.0, 2.0)
);
out vec2 vUv;
void main() {
  gl_Position = vec4(kPos[gl_VertexID], 0.0, 1.0);
  vUv = kUv[gl_VertexID];
}
"""

  private const val FRAG = """#version 300 es
precision highp float;
in vec2 vUv;
out vec4 oFrag;
uniform float uLevel;
uniform float uIntensity;
uniform sampler2D uSource;
uniform sampler2D uHaldLut;
vec2 haldIndexToUV(float idx, float W) {
  float x = mod(idx, W);
  float y = floor(idx / W);
  return vec2((x + 0.5) / W, (y + 0.5) / W);
}
vec3 sampleHaldCell(sampler2D tex, float r, float g, float b, float cSize, float W) {
  float idx = b * cSize * cSize + g * cSize + r;
  return texture(tex, haldIndexToUV(idx, W)).rgb;
}
vec3 applyHaldTrilinear(sampler2D tex, vec3 color, float level) {
  float cSize = level * level;
  vec3 p = color * (cSize - 1.0);
  vec3 c0 = floor(p);
  vec3 t = p - c0;
  c0 = clamp(c0, vec3(0.0), vec3(cSize - 1.0));
  vec3 c1 = min(c0 + 1.0, vec3(cSize - 1.0));
  float W = level * level * level;
  vec3 s000 = sampleHaldCell(tex, c0.r, c0.g, c0.b, cSize, W);
  vec3 s100 = sampleHaldCell(tex, c1.r, c0.g, c0.b, cSize, W);
  vec3 s010 = sampleHaldCell(tex, c0.r, c1.g, c0.b, cSize, W);
  vec3 s110 = sampleHaldCell(tex, c1.r, c1.g, c0.b, cSize, W);
  vec3 s001 = sampleHaldCell(tex, c0.r, c0.g, c1.b, cSize, W);
  vec3 s101 = sampleHaldCell(tex, c1.r, c0.g, c1.b, cSize, W);
  vec3 s011 = sampleHaldCell(tex, c0.r, c1.g, c1.b, cSize, W);
  vec3 s111 = sampleHaldCell(tex, c1.r, c1.g, c1.b, cSize, W);
  vec3 x00 = mix(s000, s100, t.r);
  vec3 x10 = mix(s010, s110, t.r);
  vec3 x01 = mix(s001, s101, t.r);
  vec3 x11 = mix(s011, s111, t.r);
  vec3 y0 = mix(x00, x10, t.g);
  vec3 y1 = mix(x01, x11, t.g);
  return mix(y0, y1, t.b);
}
void main() {
  vec2 uv = vUv;
  vec3 src = texture(uSource, uv).rgb;
  vec3 graded = applyHaldTrilinear(uHaldLut, src, uLevel);
  vec3 outRgb = mix(src, graded, uIntensity);
  oFrag = vec4(outRgb, 1.0);
}
"""

  fun applyLut(
    imagePath: String,
    lutPath: String,
    intensity: Float,
  ): String {
    val ctx =
      NitroModules.applicationContext
        ?: throw IllegalStateException("NitroModules is not ready (no application context)")

    if (lutPath.endsWith(".cube", ignoreCase = true)) {
      return EglCubeLutRenderer.applyLut(
        imagePath = imagePath,
        cubePath = lutPath,
        intensity = intensity,
        ctx = ctx,
      )
    }

    val sourceBmp =
      BitmapFactory.decodeFile(imagePath, BitmapFactory.Options().apply { inScaled = false })
        ?: throw IllegalStateException("Could not load image: $imagePath")
    val lutBmp =
      BitmapFactory.decodeFile(lutPath, BitmapFactory.Options().apply { inScaled = false })
        ?: throw IllegalStateException("Could not load LUT: $lutPath")
    if (lutBmp.width != lutBmp.height) {
      throw IllegalStateException("Invalid Hald CLUT: must be square, got ${lutBmp.width}x${lutBmp.height}")
    }
    val level = haldLevelFromLutSide(lutBmp.width)
    val w = sourceBmp.width
    val h = sourceBmp.height

    val egl = EglState()
    return try {
      egl.makeCurrent()
      val program = buildProgram()
      val uLevel = GLES30.glGetUniformLocation(program, "uLevel")
      val uInt = GLES30.glGetUniformLocation(program, "uIntensity")
      val uSource = GLES30.glGetUniformLocation(program, "uSource")
      val uHaldLut = GLES30.glGetUniformLocation(program, "uHaldLut")

      val fbo = IntArray(1)
      val colorTex = IntArray(1)
      val sourceTex = IntArray(1)
      val haldTex = IntArray(1)
      val vao = IntArray(1)
      GLES30.glGenVertexArrays(1, vao, 0)
      GLES30.glBindVertexArray(vao[0])
      GLES30.glGenFramebuffers(1, fbo, 0)
      GLES30.glGenTextures(1, colorTex, 0)
      GLES30.glGenTextures(1, sourceTex, 0)
      GLES30.glGenTextures(1, haldTex, 0)
      try {
        initRgbaTexture2D(
          colorTex[0],
          w,
          h,
        )
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sourceTex[0])
        GLUtils.texImage2D(
          GLES30.GL_TEXTURE_2D,
          0,
          sourceBmp,
          0,
        )
        checkGl("texImage2D source")
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, haldTex[0])
        GLUtils.texImage2D(
          GLES30.GL_TEXTURE_2D,
          0,
          lutBmp,
          0,
        )
        checkGl("texImage2D lut")
        bindLinearClamp(sourceTex[0])
        bindLinearClamp(haldTex[0])
        bindLinearClamp(colorTex[0])
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
        GLES30.glFramebufferTexture2D(
          GLES30.GL_FRAMEBUFFER,
          GLES30.GL_COLOR_ATTACHMENT0,
          GLES30.GL_TEXTURE_2D,
          colorTex[0],
          0,
        )
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
          throw IllegalStateException("FBO incomplete: 0x${Integer.toHexString(status)}")
        }
        GLES30.glViewport(0, 0, w, h)
        GLES30.glUseProgram(program)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUniform1f(uLevel, level)
        GLES30.glUniform1f(uInt, intensity)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sourceTex[0])
        GLES30.glUniform1i(uSource, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, haldTex[0])
        GLES30.glUniform1i(uHaldLut, 1)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        checkGl("draw")
        val bytes = readPixelsRgba(w, h)
        val outFile = File.createTempFile("lut_", ".png", ctx.cacheDir)
        FileOutputStream(outFile).use { fos ->
          val argb = rgbaToArgbTopDown(
            bytes,
            w,
            h,
          )
          val bitmap = Bitmap.createBitmap(
            w,
            h,
            Bitmap.Config.ARGB_8888,
          )
          bitmap.setPixels(
            argb,
            0,
            w,
            0,
            0,
            w,
            h,
          )
          bitmap.compress(
            Bitmap.CompressFormat.PNG,
            100,
            fos,
          )
          bitmap.recycle()
        }
        outFile.absolutePath
      } finally {
        GLES30.glBindVertexArray(0)
        GLES30.glDeleteVertexArrays(1, vao, 0)
        GLES30.glDeleteProgram(program)
        GLES30.glDeleteFramebuffers(1, fbo, 0)
        GLES30.glDeleteTextures(1, colorTex, 0)
        GLES30.glDeleteTextures(1, sourceTex, 0)
        GLES30.glDeleteTextures(1, haldTex, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
      }
    } finally {
      sourceBmp.recycle()
      lutBmp.recycle()
      egl.release()
    }
  }

  private class EglState {
    private val egl: EGL10 = EGLContext.getEGL() as EGL10
    private var display: EGLDisplay? = null
    private var context: EGLContext? = null
    private var surface: EGLSurface? = null
    private var config: EGLConfig? = null

    fun makeCurrent() {
      val d = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)
        ?: error("eglGetDisplay failed")
      if (!egl.eglInitialize(d, IntArray(2))) {
        error("eglInitialize failed")
      }
      this.display = d
      val attribs = intArrayOf(
        EGL10.EGL_RED_SIZE, 8,
        EGL10.EGL_GREEN_SIZE, 8,
        EGL10.EGL_BLUE_SIZE, 8,
        EGL10.EGL_ALPHA_SIZE, 8,
        EGL10.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES3_BIT,
        EGL10.EGL_SURFACE_TYPE, EGL10.EGL_PBUFFER_BIT,
        EGL10.EGL_NONE,
      )
      val configs = arrayOfNulls<EGLConfig>(1)
      val n = IntArray(1)
      if (!egl.eglChooseConfig(d, attribs, configs, 1, n) || n[0] < 1) {
        error("eglChooseConfig failed")
      }
      val c = configs[0]!!
      this.config = c
      val ctxAttrs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL10.EGL_NONE)
      val ectx = egl.eglCreateContext(d, c, EGL10.EGL_NO_CONTEXT, ctxAttrs)
        ?: error("eglCreateContext failed")
      this.context = ectx
      val sattrs = intArrayOf(EGL10.EGL_WIDTH, 2, EGL10.EGL_HEIGHT, 2, EGL10.EGL_NONE)
      val surf = egl.eglCreatePbufferSurface(d, c, sattrs) ?: error("eglCreatePbufferSurface failed")
      this.surface = surf
      if (!egl.eglMakeCurrent(d, surf, surf, ectx)) {
        error("eglMakeCurrent failed")
      }
    }

    fun release() {
      val d = display
      if (d != null) {
        egl.eglMakeCurrent(d, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT)
        context?.let { egl.eglDestroyContext(d, it) }
        surface?.let { egl.eglDestroySurface(d, it) }
        egl.eglTerminate(d)
      }
    }
  }

  private fun haldLevelFromLutSide(side: Int): Float {
    val s = side.toFloat()
    val r = Math.cbrt(s.toDouble())
    val l = Math.round(r).toInt()
    if (l < 2) {
      throw IllegalStateException("Invalid Hald CLUT: side = $side")
    }
    val check = Math.pow(l.toDouble(), 3.0)
    if (Math.abs(check - s) > 0.5) {
      throw IllegalStateException("Invalid Hald CLUT: expected side = L^3 (e.g. 64, 512), got $side")
    }
    return l.toFloat()
  }

  private fun buildProgram(): Int {
    val vs = compileShader(GLES30.GL_VERTEX_SHADER, VERT)
    val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, FRAG)
    val p = GLES30.glCreateProgram()
    GLES30.glAttachShader(p, vs)
    GLES30.glAttachShader(p, fs)
    GLES30.glLinkProgram(p)
    val link = IntArray(1)
    GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, link, 0)
    GLES30.glDeleteShader(vs)
    GLES30.glDeleteShader(fs)
    if (link[0] != GLES30.GL_TRUE) {
      val log = GLES30.glGetProgramInfoLog(p)
      GLES30.glDeleteProgram(p)
      error("GL program: $log")
    }
    return p
  }

  private fun compileShader(
    type: Int,
    src: String,
  ): Int {
    val s = GLES30.glCreateShader(type)
    GLES30.glShaderSource(s, src)
    GLES30.glCompileShader(s)
    val stat = IntArray(1)
    GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, stat, 0)
    if (stat[0] != GLES30.GL_TRUE) {
      val log = GLES30.glGetShaderInfoLog(s)
      GLES30.glDeleteShader(s)
      error("GL shader: $log")
    }
    return s
  }

  private fun initRgbaTexture2D(
    tex: Int,
    w: Int,
    h: Int,
  ) {
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
    GLES30.glTexImage2D(
      GLES30.GL_TEXTURE_2D,
      0,
      GLES30.GL_RGBA,
      w,
      h,
      0,
      GLES30.GL_RGBA,
      GLES30.GL_UNSIGNED_BYTE,
      null,
    )
  }

  private fun bindLinearClamp(tex: Int) {
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
    GLES30.glTexParameteri(
      GLES30.GL_TEXTURE_2D,
      GLES30.GL_TEXTURE_MIN_FILTER,
      GLES30.GL_LINEAR,
    )
    GLES30.glTexParameteri(
      GLES30.GL_TEXTURE_2D,
      GLES30.GL_TEXTURE_MAG_FILTER,
      GLES30.GL_LINEAR,
    )
    GLES30.glTexParameteri(
      GLES30.GL_TEXTURE_2D,
      GLES30.GL_TEXTURE_WRAP_S,
      GLES30.GL_CLAMP_TO_EDGE,
    )
    GLES30.glTexParameteri(
      GLES30.GL_TEXTURE_2D,
      GLES30.GL_TEXTURE_WRAP_T,
      GLES30.GL_CLAMP_TO_EDGE,
    )
  }

  private fun readPixelsRgba(
    w: Int,
    h: Int,
  ): ByteArray {
    val buf = ByteArray(w * h * 4)
    val b = ByteBuffer.wrap(buf)
    b.order(ByteOrder.nativeOrder())
    GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, b)
    checkGl("readPixels")
    return buf
  }

  private fun rgbaToArgbTopDown(
    rgba: ByteArray,
    w: Int,
    h: Int,
  ): IntArray {
    val row = w * 4
    val out = IntArray(w * h)
    for (y in 0 until h) {
      val srcY = h - 1 - y
      val srcRow = srcY * row
      for (x in 0 until w) {
        val s = srcRow + x * 4
        val r = rgba[s].toInt() and 0xff
        val g = rgba[s + 1].toInt() and 0xff
        val b = rgba[s + 2].toInt() and 0xff
        out[y * w + x] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
      }
    }
    return out
  }

  private fun checkGl(tag: String) {
    var err = GLES30.glGetError()
    if (err != GLES30.GL_NO_ERROR) {
      val stack = StringBuilder("0x${Integer.toHexString(err)}")
      while (true) {
        err = GLES30.glGetError()
        if (err == GLES30.GL_NO_ERROR) break
        stack.append(" 0x${Integer.toHexString(err)}")
      }
      error("$tag: GL $stack")
    }
  }
}
