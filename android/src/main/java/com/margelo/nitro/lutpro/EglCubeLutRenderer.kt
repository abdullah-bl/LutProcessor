@file:Suppress("ktlint:standard:max-line-length")

package com.margelo.nitro.lutpro

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.EGL14
import android.opengl.GLES30
import android.opengl.GLUtils
import java.io.FileOutputStream
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.egl.EGLSurface

/**
 * 3D LUT from Iridas/Adobe .cube (ASCII) via OpenGL ES 3 — hardware trilinear on [sampler3D].
 */
internal object EglCubeLutRenderer {
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
precision highp sampler2D;
precision highp sampler3D;
in vec2 vUv;
out vec4 oFrag;
uniform vec3 uDomainMin;
uniform vec3 uDomainMax;
uniform float uIntensity;
uniform sampler2D uSource;
uniform sampler3D uLut3d;
void main() {
  vec2 uv = vUv;
  vec3 src = texture(uSource, uv).rgb;
  vec3 denom = max(uDomainMax - uDomainMin, vec3(1e-8));
  vec3 p = (src - uDomainMin) / denom;
  p = clamp(p, 0.0, 1.0);
  vec3 graded = texture(uLut3d, p).rgb;
  oFrag = vec4(mix(src, graded, uIntensity), 1.0);
}
"""

  fun applyLut(
    imagePath: String,
    cubePath: String,
    intensity: Float,
    ctx: Context,
  ): String {
    val cube =
      try {
        CubeLutParser.parseFile(cubePath)
      } catch (e: Exception) {
        throw IllegalStateException("Invalid or unreadable .cube: ${e.message}", e)
      }
    val n = cube.size
    val sourceBmp =
      BitmapFactory.decodeFile(imagePath, BitmapFactory.Options().apply { inScaled = false })
        ?: throw IllegalStateException("Could not load image: $imagePath")
    val w = sourceBmp.width
    val h = sourceBmp.height
    val egl = EglState()
    return try {
      egl.makeCurrent()
      val program = buildProgram()
      val uDomainMin = GLES30.glGetUniformLocation(program, "uDomainMin")
      val uDomainMax = GLES30.glGetUniformLocation(program, "uDomainMax")
      val uIntensity = GLES30.glGetUniformLocation(program, "uIntensity")
      val uSource = GLES30.glGetUniformLocation(program, "uSource")
      val uLut3d = GLES30.glGetUniformLocation(program, "uLut3d")
      val fbo = intArrayOf(0)
      val colorTex = intArrayOf(0)
      val sourceTex = intArrayOf(0)
      val lut3d = intArrayOf(0)
      val vao = intArrayOf(0)
      GLES30.glGenVertexArrays(1, vao, 0)
      GLES30.glBindVertexArray(vao[0])
      GLES30.glGenFramebuffers(1, fbo, 0)
      GLES30.glGenTextures(1, colorTex, 0)
      GLES30.glGenTextures(1, sourceTex, 0)
      GLES30.glGenTextures(1, lut3d, 0)
      try {
        initRgbaTexture2D(colorTex[0], w, h)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sourceTex[0])
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, sourceBmp, 0)
        checkGl("texImage2D source (cube path)")
        bindLinearClamp2d(sourceTex[0])
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lut3d[0])
        val buf: Buffer =
          ByteBuffer.wrap(cube.rgba8).order(ByteOrder.nativeOrder())
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage3D(
          GLES30.GL_TEXTURE_3D,
          0,
          GLES30.GL_RGBA8,
          n,
          n,
          n,
          0,
          GLES30.GL_RGBA,
          GLES30.GL_UNSIGNED_BYTE,
          buf,
        )
        checkGl("glTexImage3D")
        bindLinearClamp3d(lut3d[0])
        bindLinearClamp2d(colorTex[0])
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
        GLES30.glFramebufferTexture2D(
          GLES30.GL_FRAMEBUFFER,
          GLES30.GL_COLOR_ATTACHMENT0,
          GLES30.GL_TEXTURE_2D,
          colorTex[0],
          0,
        )
        if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE) {
          throw IllegalStateException("FBO incomplete (cube path)")
        }
        GLES30.glViewport(0, 0, w, h)
        GLES30.glUseProgram(program)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUniform3f(
          uDomainMin,
          cube.domainMin[0],
          cube.domainMin[1],
          cube.domainMin[2],
        )
        GLES30.glUniform3f(
          uDomainMax,
          cube.domainMax[0],
          cube.domainMax[1],
          cube.domainMax[2],
        )
        GLES30.glUniform1f(uIntensity, intensity)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sourceTex[0])
        GLES30.glUniform1i(uSource, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lut3d[0])
        GLES30.glUniform1i(uLut3d, 1)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        checkGl("draw cube")
        val bytes = readPixelsRgba(w, h)
        val outFile = File.createTempFile("lut_", ".png", ctx.cacheDir)
        FileOutputStream(outFile).use { fos ->
          val argb = rgbaToArgbTopDown(bytes, w, h)
          val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
          bitmap.setPixels(argb, 0, w, 0, 0, w, h)
          bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
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
        GLES30.glDeleteTextures(1, lut3d, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
      }
    } finally {
      sourceBmp.recycle()
      egl.release()
    }
  }

  private fun bindLinearClamp3d(id: Int) {
    GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, id)
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
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

  private fun bindLinearClamp2d(tex: Int) {
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
      error("GL program (cube): $log")
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
      error("GL shader (cube): $log")
    }
    return s
  }

  private class EglState {
    private val egl: EGL10 = javax.microedition.khronos.egl.EGLContext.getEGL() as EGL10
    private var display: EGLDisplay? = null
    private var context: EGLContext? = null
    private var surface: EGLSurface? = null

    fun makeCurrent() {
      val d = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY) ?: error("eglGetDisplay failed")
      if (!egl.eglInitialize(d, IntArray(2))) error("eglInitialize failed")
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
      val ctxAttrs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL10.EGL_NONE)
      val ectx = egl.eglCreateContext(d, c, EGL10.EGL_NO_CONTEXT, ctxAttrs) ?: error("eglCreateContext failed")
      this.context = ectx
      val sattrs = intArrayOf(EGL10.EGL_WIDTH, 2, EGL10.EGL_HEIGHT, 2, EGL10.EGL_NONE)
      val surf = egl.eglCreatePbufferSurface(d, c, sattrs) ?: error("eglCreatePbufferSurface failed")
      this.surface = surf
      if (!egl.eglMakeCurrent(d, surf, surf, ectx)) error("eglMakeCurrent failed")
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
}
