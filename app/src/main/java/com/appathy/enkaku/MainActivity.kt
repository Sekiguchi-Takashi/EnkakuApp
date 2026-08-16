package com.appathy.enkaku

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.zip.ZipInputStream

class MainActivity : AppCompatActivity() {

    private val REQ_IMPORT = 41

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btnEnable).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        findViewById<Button>(R.id.btnSwitch).setOnClickListener {
            val imm = getSystemService(InputMethodManager::class.java)
            imm?.showInputMethodPicker()
        }
        findViewById<Button>(R.id.btnLearn).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "一覧から「Enkaku 常駐学習」をONにしてください", Toast.LENGTH_LONG).show()
        }
        findViewById<Button>(R.id.btnImport).setOnClickListener {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.type = "*/*"
            startActivityForResult(i, REQ_IMPORT)
        }
    }

    @Deprecated("classic result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_IMPORT || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        val n = importGboardList(uri)
        Toast.makeText(
            this,
            if (n > 0) n.toString() + "語を予測に取り込みました" else "単語リストが見つかりませんでした",
            Toast.LENGTH_LONG
        ).show()
    }

    // Gboardのエクスポート（ZIP内のdictionary.txt: 単語<TAB>よみ<TAB>言語）を取り込む。
    // 素のテキストファイルが選ばれた場合もそのまま解釈する。
    private fun importGboardList(uri: Uri): Int {
        var count = 0
        try {
            contentResolver.openInputStream(uri)?.use { ins ->
                val head = java.io.BufferedInputStream(ins, 8192)
                head.mark(4)
                val sig = ByteArray(2)
                val read = head.read(sig)
                head.reset()
                val isZip = read == 2 && sig[0] == 'P'.code.toByte() && sig[1] == 'K'.code.toByte()
                if (isZip) {
                    val z = ZipInputStream(head)
                    var e = z.nextEntry
                    while (e != null) {
                        if (!e.isDirectory && e.name.endsWith(".txt")) {
                            count += importLines(z.bufferedReader())
                        }
                        e = z.nextEntry
                    }
                } else {
                    count += importLines(head.bufferedReader())
                }
            }
        } catch (e: Throwable) {
            return count
        }
        return count
    }

    private fun importLines(br: java.io.BufferedReader): Int {
        var count = 0
        for (line in br.lineSequence()) {
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split("\t")
            if (parts.size < 2) continue
            val word = parts[0].trim()
            val reading = parts[1].trim()
            if (word.isEmpty() || reading.isEmpty()) continue
            if (word.length > TextSlots.MAX_LEN || reading.length > 24) continue
            Predictor.learn(this, reading, word)
            count++
        }
        return count
    }
}
