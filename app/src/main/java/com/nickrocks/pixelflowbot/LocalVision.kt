package com.nickrocks.pixelflowbot

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.nickrocks.pixelflowbot.core.vlm.VisionModel
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException

/**
 * The on-device vision-language model (Google LiteRT-LM running a .litertlm model such as Gemma 3n).
 * Everything runs on the phone's GPU or CPU: the screenshot is written to this app's private cache
 * and read back by the model; nothing is sent anywhere (the app has no internet permission).
 *
 * One engine is loaded per process and shared (loading takes ~10–30 s and a few GB of memory).
 * Each question gets a fresh conversation so earlier screenshots don't fill the context.
 */
class LocalVision private constructor(
    private val engine: Engine,
    private val label: String,
    private val work: File,
) : VisionModel {

    override fun name(): String = label

    @Throws(IOException::class)
    override fun ask(system: String, jpeg: ByteArray?, prompt: String, maxTokens: Int): VisionModel.Reply {
        val t0 = System.currentTimeMillis()
        val out: String = synchronized(LOCK) {
            try {
                engine.createConversation(ConversationConfig(systemInstruction = Contents.of(system))).use { conv ->
                    val msg = if (jpeg != null) {
                        val f = File(work, "frame.jpg")
                        f.writeBytes(jpeg)
                        Contents.of(Content.ImageFile(f.absolutePath), Content.Text(prompt))
                    } else {
                        Contents.of(Content.Text(prompt))
                    }
                    runBlocking { conv.sendMessage(msg) }.toString()
                }
            } catch (e: Throwable) {
                throw IOException("on-device model failed: " + (e.message ?: e.toString()), e)
            }
        }
        val r = VisionModel.Reply()
        r.text = out
        r.ms = System.currentTimeMillis() - t0
        r.model = label
        return r
    }

    companion object {
        private val LOCK = Any()
        private var current: LocalVision? = null
        private var currentPath: String? = null

        /** "GPU" or "CPU" for the loaded model, or "" if none is loaded. */
        @JvmStatic
        var backend: String = ""
            private set

        /** How long the last load took, in ms. */
        @JvmStatic
        var loadMs: Long = 0
            private set

        @JvmStatic
        fun isLoaded(path: String): Boolean = synchronized(LOCK) { current != null && currentPath == path }

        /** The shared model for this file, loading it if needed (slow — call off the main thread). */
        @JvmStatic
        @Throws(IOException::class)
        fun get(ctx: Context, path: String, name: String, preferGpu: Boolean): LocalVision = synchronized(LOCK) {
            val have = current
            if (have != null && currentPath == path) return have
            releaseLocked()
            if (!File(path).isFile) throw IOException("the model file is missing — import it again in AI GAME BOT → On-device AI")
            val work = File(ctx.cacheDir, "vlm").apply { mkdirs() }
            var last: Throwable? = null
            for (gpu in if (preferGpu) listOf(true, false) else listOf(false)) {
                val t0 = System.currentTimeMillis()
                try {
                    val cfg = EngineConfig(
                        modelPath = path,
                        backend = if (gpu) Backend.GPU() else Backend.CPU(),
                        visionBackend = if (gpu) Backend.GPU() else Backend.CPU(),
                        cacheDir = work.path,
                    )
                    val engine = Engine(cfg)
                    runBlocking { engine.initialize() }
                    val lv = LocalVision(engine, name, work)
                    current = lv
                    currentPath = path
                    backend = if (gpu) "GPU" else "CPU"
                    loadMs = System.currentTimeMillis() - t0
                    return lv
                } catch (t: Throwable) {
                    last = t
                }
            }
            throw IOException("couldn't load the model: " + (last?.message ?: last.toString()), last)
        }

        /** Frees the model's memory. */
        @JvmStatic
        fun release() = synchronized(LOCK) { releaseLocked() }

        private fun releaseLocked() {
            try { current?.engine?.close() } catch (_: Throwable) { }
            current = null
            currentPath = null
            backend = ""
        }
    }
}
