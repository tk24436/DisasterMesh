package com.example.disastermesh.ai

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

/**
 * GemmaEngine — Singleton wrapper for on-device LLM inference.
 *
 * On Snapdragon 8 Elite (SM8850) devices with the model file present,
 * it runs Gemma 4 2B entirely on the Hexagon V81 NPU via LiteRT-LM.
 *
 * On any other device, or if the model file is missing, isReady = false
 * and the caller falls back to FirstAidKnowledgeBase.
 */
object GemmaEngine {

    private const val TAG = "GemmaEngine"
    const val MODEL_FILENAME = "gemma4_2b_SM8850.litertlm"

    // ── State ──────────────────────────────────────────────────────────────

    enum class Status { IDLE, LOADING, READY, ERROR, UNSUPPORTED }

    @Volatile var status: Status = Status.IDLE
        private set

    @Volatile var lastError: String = ""
        private set

    val isReady get() = status == Status.READY

    // ── Device / model detection ───────────────────────────────────────────

    /** True only on arm64 devices (necessary but not sufficient for NPU) */
    val isArm64: Boolean
        get() = Build.SUPPORTED_ABIS.contains("arm64-v8a")

    /** True when the model file has been pushed to the expected path */
    fun modelExists(context: Context): Boolean =
        getModelFile(context).exists()

    fun getModelFile(context: Context): File =
        File(context.getExternalFilesDir(null), MODEL_FILENAME)

    // ── LiteRT-LM handle ──────────────────────────────────────────────────
    // We use reflection to avoid a hard compile dependency on the SDK
    // until the .so files are present in jniLibs. This keeps the app
    // installable on non-Snapdragon devices without crashing at class load.

    private var llmInference: Any? = null

    // ── Initialization ────────────────────────────────────────────────────

    /**
     * Attempt to load the model and warm up the NPU.
     * Safe to call multiple times — no-ops if already READY.
     * Runs synchronously; call from a background thread.
     */
    fun initialize(context: Context): Boolean {
        if (status == Status.READY) return true
        if (!isArm64) {
            status = Status.UNSUPPORTED
            lastError = "Device is not arm64-v8a"
            Log.w(TAG, lastError)
            return false
        }
        if (!modelExists(context)) {
            status = Status.UNSUPPORTED
            lastError = "Model file not found: ${getModelFile(context).absolutePath}"
            Log.w(TAG, lastError)
            return false
        }

        status = Status.LOADING
        return try {
            val modelPath = getModelFile(context).absolutePath
            val nativeLibDir = context.applicationInfo.nativeLibraryDir

            // Reflection-based init so the class compiles without the SDK JAR
            val configClass = Class.forName("com.google.ai.edge.litert.lm.LlmInferenceConfig")
            val builderClass = Class.forName("com.google.ai.edge.litert.lm.LlmInferenceConfig\$Builder")
            val backendClass = Class.forName("com.google.ai.edge.litert.lm.Backend")
            val npuClass = Class.forName("com.google.ai.edge.litert.lm.Backend\$NPU")

            val npuInstance = npuClass.getDeclaredConstructor(String::class.java)
                .newInstance(nativeLibDir)

            val builder = builderClass.getDeclaredConstructor().newInstance()
            builderClass.getMethod("setModelPath", String::class.java).invoke(builder, modelPath)
            builderClass.getMethod("setMaxTokens", Int::class.java).invoke(builder, 512)
            builderClass.getMethod("setBackend", backendClass).invoke(builder, npuInstance)
            val config = builderClass.getMethod("build").invoke(builder)

            val inferenceClass = Class.forName("com.google.ai.edge.litert.lm.LlmInference")
            llmInference = inferenceClass.getMethod("create", Context::class.java, configClass)
                .invoke(null, context.applicationContext, config)

            status = Status.READY
            Log.i(TAG, "Gemma 4 2B initialized on NPU ✓ (model=$modelPath)")
            true
        } catch (e: Exception) {
            status = Status.ERROR
            lastError = e.message ?: "Unknown init error"
            Log.e(TAG, "GemmaEngine init failed: $lastError", e)
            false
        }
    }

    // ── Inference ─────────────────────────────────────────────────────────

    /**
     * Generate a streamed first-aid response.
     *
     * [onToken] is called on each new token fragment as it is produced.
     * [onDone]  is called once generation is fully complete.
     * [onError] is called if inference fails.
     *
     * Always call from a background thread; callbacks are delivered on
     * the same thread (caller should post to main thread for UI updates).
     */
    fun generateResponse(
        query: String,
        onToken: (String) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        val llm = llmInference
        if (llm == null || status != Status.READY) {
            onError("Engine not ready (status=$status)")
            return
        }

        val prompt = buildPrompt(query)
        try {
            // LiteRT-LM API: generateResponseAsync(prompt, callback)
            val listenerClass = Class.forName(
                "com.google.ai.edge.litert.lm.LlmInference\$LlmInferenceCallback"
            )

            val proxy = java.lang.reflect.Proxy.newProxyInstance(
                listenerClass.classLoader,
                arrayOf(listenerClass)
            ) { _, method, args ->
                when (method.name) {
                    "onPartialResponse" -> {
                        val token = args?.getOrNull(0) as? String ?: ""
                        onToken(token)
                    }
                    "onResponse" -> {
                        onDone()
                    }
                    "onError" -> {
                        val err = args?.getOrNull(0)?.toString() ?: "Unknown error"
                        onError(err)
                    }
                }
                null
            }

            val generateMethod = llm.javaClass.getMethod(
                "generateResponseAsync", String::class.java, listenerClass
            )
            generateMethod.invoke(llm, prompt, proxy)

        } catch (e: Exception) {
            Log.e(TAG, "Inference error: ${e.message}", e)
            onError(e.message ?: "Inference failed")
        }
    }

    // ── Cleanup ───────────────────────────────────────────────────────────

    fun close() {
        try {
            llmInference?.javaClass?.getMethod("close")?.invoke(llmInference)
        } catch (_: Exception) {}
        llmInference = null
        status = Status.IDLE
    }

    // ── Prompt builder ────────────────────────────────────────────────────

    private fun buildPrompt(query: String): String = """
<start_of_turn>user
You are an offline emergency first-aid assistant for disaster scenarios in India.
Answer concisely with clear numbered steps. Keep responses under 150 words.
Emergency contacts: 112 (universal), 102/108 (ambulance), 101 (fire), 100 (police).
Only answer first aid, emergency preparedness, and safety questions.
If the question is not related to emergencies, politely redirect to first aid topics.

Question: $query
<end_of_turn>
<start_of_turn>model
""".trimIndent()
}
