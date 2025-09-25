package com.nfcbridgeapp

import android.content.Intent
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.NfcAdapter.ReaderCallback
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.tech.Ndef
import android.nfc.Tag
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.Arrays
import kotlin.concurrent.thread
import android.widget.Button

class MainActivity : AppCompatActivity(), ReaderCallback {

    private var nfcAdapter: NfcAdapter? = null
    private var callbackUrl: String? = null
    private val TAG = "NFCBridge"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No hace falta UI compleja; puedes poner un layout minimal si quieres.
        setContentView(R.layout.activity_main)


        // leer parámetro desde el intent: nfcbridgeapp://scan?url_destino=...
        intent?.data?.let { uri ->
            callbackUrl = uri.getQueryParameter("url_destino") ?: uri.getQueryParameter("urlDestino")
            // si el pwa envía url codificada, getQueryParameter ya la decodifica
            Log.i(TAG, "CallbackUrl inicial: $callbackUrl")
        }

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            Toast.makeText(this, "Este dispositivo no tiene NFC", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val btnEscanear = findViewById<Button>(R.id.btnEscanear)
        btnEscanear.setOnClickListener {
            callbackUrl?.let { url ->
                try {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        data = Uri.parse(url)
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "No se pudo abrir la URL", Toast.LENGTH_SHORT).show()
                    Log.e(TAG, "Error al abrir URL: $url", e)
                }
            } ?: run {
                Toast.makeText(this, "No hay URL para abrir", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Activar modo lector: soporta la mayoría de tecnologías
        nfcAdapter?.enableReaderMode(
            this,
            this,
            NfcAdapter.FLAG_READER_NFC_A or
                    NfcAdapter.FLAG_READER_NFC_B or
                    NfcAdapter.FLAG_READER_NFC_F or
                    NfcAdapter.FLAG_READER_NFC_V or
                    NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS or
                    NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            null
        )
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableReaderMode(this)
    }

    // Callback que se ejecuta en hilo distinto al UI thread
    override fun onTagDiscovered(tag: Tag) {
        Log.i(TAG, "Tag descubierto: ${tag.id?.contentToString()}")
        // intentar leer como NDEF
        val result = try {
            val ndef = Ndef.get(tag)
            if (ndef != null) {
                ndef.connect()
                val msg = ndef.ndefMessage
                val parsed = if (msg != null) parseNdefMessage(msg) else "NDEF_EMPTY"
                ndef.close()
                parsed
            } else {
                // fallback: UID hex + tech list
                val idHex = bytesToHex(tag.id)
                val techs = tag.techList.joinToString(",")
                "UID:$idHex;TECHS:$techs"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error leyendo tag: ${e.message}", e)
            "ERROR:${e.message}"
        }

        Log.i(TAG, "Resultado lectura: $result")
        // envia resultado (elige estrategia)
        callbackUrl?.let { url ->
            if (url.startsWith("http://") || url.startsWith("https://")) {
                // primero intentamos hacer HTTP GET desde la app
                sendResultViaHttpGet(url, result)
            } else {
                // si no es http, abrimos navegador con la url destino (p.ej. callback app web)
                openBrowserWithResult(url, result)
            }
        } ?: run {
            // si no hubo callbackUrl: mostrar y terminar
            runOnUiThread {
                Toast.makeText(this, "Lectura: $result", Toast.LENGTH_LONG).show()
            }
        }

        // opcional: cerramos la Activity si queremos que vuelva automáticamente
        finish()
    }

    private fun parseNdefMessage(message: NdefMessage): String {
        val sb = StringBuilder()
        message.records.forEach { rec ->
            sb.append(parseRecord(rec)).append(";")
        }
        return sb.toString()
    }

    private fun parseRecord(record: NdefRecord): String {
        return try {
            val tnf = record.tnf
            when {
                tnf == NdefRecord.TNF_WELL_KNOWN && Arrays.equals(record.type, NdefRecord.RTD_TEXT) -> {
                    val payload = record.payload
                    val textEncoding = if (payload[0].toInt() and 0x80 == 0) Charsets.UTF_8 else Charsets.UTF_16
                    val languageCodeLength = payload[0].toInt() and 0x3F
                    val text = String(payload, languageCodeLength + 1, payload.size - languageCodeLength - 1, textEncoding)
                    "TEXT:$text"
                }
                tnf == NdefRecord.TNF_WELL_KNOWN && Arrays.equals(record.type, NdefRecord.RTD_URI) -> {
                    val payload = record.payload
                    val prefix = uriPrefixMap.getOrNull(payload[0].toInt()) ?: ""
                    val uri = prefix + String(payload, 1, payload.size - 1, Charsets.UTF_8)
                    "URI:$uri"
                }
                else -> {
                    "RAW:${bytesToHex(record.payload)}"
                }
            }
        } catch (e: Exception) {
            "PARSE_ERROR:${e.message}"
        }
    }

    private val uriPrefixMap = arrayOf(
        "", "http://www.", "https://www.", "http://", "https://",
        "tel:", "mailto:", "ftp://anonymous:anonymous@", "ftp://ftp.",
        "ftps://", "sftp://", "smb://", "nfs://", "ftp://", "dav://",
        "news:", "telnet://", "imap:", "rtsp://", "urn:", "pop:",
        "sip:", "sips:", "tftp:", "btspp://", "btl2cap://", "btgoep://",
        "tcpobex://", "irdaobex://", "file://", "urn:epc:id:",
        "urn:epc:tag:", "urn:epc:pat:", "urn:epc:raw:", "urn:epc:",
        "urn:nfc:"
    )

    private fun bytesToHex(bytes: ByteArray?): String {
        if (bytes == null) return ""
        val sb = StringBuilder()
        for (b in bytes) sb.append(String.format("%02X", b))
        return sb.toString()
    }

    private fun sendResultViaHttpGet(destUrl: String, payload: String) {
        thread {
            try {
                val encoded = URLEncoder.encode(payload, "UTF-8")
                val sep = if (destUrl.contains("?")) "&" else "?"
                val finalUrl = "$destUrl${sep}nfc=$encoded"
                val client = OkHttpClient()
                val request = Request.Builder().url(finalUrl).get().build()
                val response = client.newCall(request).execute()
                runOnUiThread {
                    Toast.makeText(this, "Resultado enviado (HTTP ${response.code})", Toast.LENGTH_LONG).show()
                }
                response.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error enviando GET: ${e.message}", e)
                // fallback: abrir navegador si falla
                runOnUiThread {
                    Toast.makeText(this, "Error al enviar HTTP, abriendo navegador...", Toast.LENGTH_LONG).show()
                    openBrowserWithResult(destUrl, payload)
                }
            }
        }
    }

    private fun openBrowserWithResult(destUrl: String, payload: String) {
        try {
            val encoded = URLEncoder.encode(payload, "UTF-8")
            val sep = if (destUrl.contains("?")) "&" else "?"
            val finalUrl = "$destUrl${sep}nfc=$encoded"
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(finalUrl))
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo abrir navegador: ${e.message}", e)
            runOnUiThread {
                Toast.makeText(this, "No se pudo abrir navegador: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
