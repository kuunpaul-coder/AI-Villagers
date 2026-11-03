package com.aivillagers.bridge

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var modelFile: File? = null
    private var apiServer: LocalApiServer? = null
    private lateinit var status: TextView
    private lateinit var transcript: TextView
    private lateinit var prompt: EditText
    private lateinit var loadButton: Button
    private lateinit var sendButton: Button

    companion object { private const val PICK_MODEL = 1001 }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 24, 20, 20)
        }
        val title = TextView(this).apply {
            text = "AI Villagers"; textSize = 26f; gravity = Gravity.CENTER
        }
        status = TextView(this).apply {
            text = "Выбери файл Gemma .litertlm. Модель будет скопирована в папку приложения."
            textSize = 14f; setPadding(0, 18, 0, 18)
        }
        val chooseButton = Button(this).apply {
            text = "1. Выбрать Gemma (.litertlm)"
            setOnClickListener { chooseModel() }
        }
        loadButton = Button(this).apply {
            text = "2. Загрузить модель"; isEnabled = false
            setOnClickListener { loadModel() }
        }
        transcript = TextView(this).apply {
            text = "Здесь появятся ответы Gemma."; textSize = 15f
            setPadding(8, 12, 8, 12)
        }
        val scroll = ScrollView(this).apply {
            addView(transcript)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        prompt = EditText(this).apply {
            hint = "Напиши сообщение NPC..."; minLines = 2; maxLines = 4
        }
        sendButton = Button(this).apply {
            text = "Отправить"; isEnabled = false
            setOnClickListener { sendPrompt() }
        }
        root.addView(title); root.addView(status); root.addView(chooseButton)
        root.addView(loadButton); root.addView(scroll); root.addView(prompt)
        root.addView(sendButton)
        setContentView(root)
    }

    private fun chooseModel() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
        }
        startActivityForResult(intent, PICK_MODEL)
    }

    @Deprecated("Deprecated in Android API, retained for broad device compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_MODEL || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Throwable) {}
        status.text = "Файл выбран. Копирую модель в папку приложения..."
        loadButton.isEnabled = false
        worker.execute {
            try {
                val name = queryName(uri) ?: "gemma-model.litertlm"
                require(name.lowercase().endsWith(".litertlm")) {
                    "Нужен файл с расширением .litertlm"
                }
                val dir = getExternalFilesDir("models") ?: filesDir
                dir.mkdirs()
                val destination = File(dir, "gemma-model.litertlm")
                contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "Не удалось открыть выбранный файл" }
                    destination.outputStream().buffered().use { output ->
                        input.copyTo(output, 1024 * 1024)
                    }
                }
                modelFile = destination
                main.post {
                    status.text = "Модель скопирована (" +
                        (destination.length() / (1024 * 1024)) +
                        " МБ). Нажми «Загрузить модель»."
                    loadButton.isEnabled = true
                }
            } catch (t: Throwable) {
                main.post { status.text = "Ошибка копирования: " + t.message }
            }
        }
    }

    private fun queryName(uri: Uri): String? {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) return cursor.getString(index)
        }
        return null
    }

    private fun loadModel() {
        val file = modelFile ?: return
        loadButton.isEnabled = false
        status.text = "Загружаю Gemma. Это может занять время; не закрывай приложение..."
        worker.execute {
            try {
                val candidate = Engine(EngineConfig(
                    modelPath = file.absolutePath,
                    backend = Backend.CPU(),
                    cacheDir = cacheDir.absolutePath
                ))
                candidate.initialize()
                engine = candidate
                conversation = candidate.createConversation(ConversationConfig(
                    systemInstruction = Contents.of(
                        "Ты — житель Пеликан-Тауна из Stardew Valley. " +
                        "Отвечай естественно, по-русски, сохраняй характер NPC. " +
                        "Учитывай имя NPC и контекст, переданные в сообщении. " +
                        "Не утверждай, что можешь менять игровой мир или сохранять игровые данные."
                    )
                ))
                val server = LocalApiServer(
                    isModelReady = { conversation != null },
                    handler = { request ->
                    worker.submit<JSONObject> {
                        val npc = request.optString("npc", "Житель Пеликан-Тауна")
                        val playerMessage = request.optString("message", "").trim()
                        val context = request.optString("context", "").trim()
                        require(playerMessage.isNotEmpty()) { "Поле message не должно быть пустым" }
                        val fullPrompt = buildString {
                            append("Имя персонажа: ").append(npc).append(".\\n")
                            if (context.isNotEmpty()) {
                                append("Контекст игры и отношений: ").append(context).append("\\n")
                            }
                            append("Реплика игрока: ").append(playerMessage)
                        }
                        val answer = conversation?.sendMessage(fullPrompt)?.toString()
                            ?: throw IllegalStateException("Модель ещё не загружена")
                        JSONObject().put("reply", answer).put("npc", npc)
                    }.get(120, TimeUnit.SECONDS)
                })
                server.start()
                apiServer = server
                main.post {
                    status.text = "Gemma загружена. Локальный API работает: 127.0.0.1:8765"
                    sendButton.isEnabled = true
                }
            } catch (t: Throwable) {
                main.post {
                    status.text = "Не удалось загрузить модель/API: " + t.message
                    loadButton.isEnabled = true
                }
            }
        }
    }

    private fun sendPrompt() {
        val text = prompt.text.toString().trim()
        if (text.isEmpty()) return
        sendButton.isEnabled = false
        transcript.append("\n\nТы: " + text + "\nGemma: думает...")
        prompt.text.clear()
        worker.execute {
            try {
                val answer = conversation?.sendMessage(text)?.toString() ?: "Нет ответа"
                main.post {
                    transcript.append("\n" + answer)
                    sendButton.isEnabled = true
                }
            } catch (t: Throwable) {
                main.post {
                    transcript.append("\nОшибка: " + t.message)
                    sendButton.isEnabled = true
                }
            }
        }
    }

    override fun onDestroy() {
        apiServer?.stop()
        worker.execute {
            try { conversation?.close() } catch (_: Throwable) {}
            try { engine?.close() } catch (_: Throwable) {}
            conversation = null; engine = null
        }
        worker.shutdown()
        super.onDestroy()
    }
}
