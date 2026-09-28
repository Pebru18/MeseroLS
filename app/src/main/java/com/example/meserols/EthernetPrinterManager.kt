package com.example.meserols

import android.util.Log
import java.io.OutputStream
import java.net.Socket
import java.nio.charset.Charset

object EthernetPrinterManager {

    private const val IP     = "192.168.100.50"
    private const val PUERTO = 9100

    // ── Font-size constants used by imprimirPedido ────────────────────────────
    const val FONT_SIZE_SMALL  = 0
    const val FONT_SIZE_MEDIUM = 1
    const val FONT_SIZE_LARGE  = 2

    // ══════════════════════════════════════════════════════════════════════════
    //  TAMAÑOS DE FUENTE — cambia estos dos valores para ajustar el ticket
    // ══════════════════════════════════════════════════════════════════════════

    // Número de mesa (gigante, arriba de la comanda). Comando GS ! n:
    //   0x22 = 3× alto y ancho   0x33 = 4× alto y ancho   0x44 = 5× alto y ancho
    //
    // LÍMITE: "<< 12 >>" son 9 chars. A 4× (0x33) usa 36 de 42 disponibles → OK.
    //         A 5× (0x44) usaría 45 → desborda y el último ">" cae en otra línea.
    //         No subas de 0x33 si usas el formato "<< N >>".
    private const val BYTE_FUENTE_MESA: Byte = 0x33   // 4× — cambia aquí

    // Cuerpo del pedido (líneas de platos, cantidades, actualizaciones, etc.)
    //   0x00 = normal (1×)         ← texto pequeño, más chars por línea
    //   0x10 = 2× solo alto        ← letras más altas, mismo ancho (recomendado)
    //   0x11 = 2× alto y ancho     ← más grande, mitad de chars por línea
    //   0x20 = 3× solo alto        ← muy alto, mismo ancho
    //   0x22 = 3× alto y ancho     ← grande, un tercio de chars por línea
    private const val BYTE_FUENTE_CUERPO: Byte = 0x01  // 2× alto — cambia aquí

    // ══════════════════════════════════════════════════════════════════════════

    // ── ESC/POS command helpers ───────────────────────────────────────────────

    private val ESC_RESET    = byteArrayOf(0x1B, 0x40)
    private val ESC_CENTER   = byteArrayOf(0x1B, 0x61, 0x01)
    private val ESC_LEFT     = byteArrayOf(0x1B, 0x61, 0x00)
    private val ESC_BOLD_ON  = byteArrayOf(0x1B, 0x45, 0x01)
    private val ESC_BOLD_OFF = byteArrayOf(0x1B, 0x45, 0x00)
    private val PAPER_CUT    = byteArrayOf(0x1D, 0x56, 0x41, 0x10)

    private fun fontSizeCmd(size: Int): ByteArray = when (size) {
        FONT_SIZE_LARGE  -> byteArrayOf(0x1D, 0x21, 0x11)  // 2× width + 2× height
        FONT_SIZE_MEDIUM -> byteArrayOf(0x1D, 0x21, 0x00)  // normal (reset)
        else             -> byteArrayOf(0x1D, 0x21, 0x00)
    }

    private val FONT_GIANT_MESA   = byteArrayOf(0x1D, 0x21, BYTE_FUENTE_MESA)
    private val FONT_NORMAL       = byteArrayOf(0x1D, 0x21, 0x00)
    private val FONT_CUERPO_PEDIDO = byteArrayOf(0x1D, 0x21, BYTE_FUENTE_CUERPO)

    private val charsetCp850: Charset = Charset.forName("Cp850")

    // ── PUBLIC API ────────────────────────────────────────────────────────────

    /**
     * Basic send — UTF-8 text + paper cut. Kept for backward compatibility.
     */
    fun imprimir(
        texto: String,
        onSuccess: () -> Unit = {},
        onError: (Exception) -> Unit = {}
    ) {
        Thread {
            try {
                val socket = Socket(IP, PUERTO)
                val out: OutputStream = socket.getOutputStream()
                out.write(texto.toByteArray(Charsets.UTF_8))
                out.write(PAPER_CUT)
                out.flush()
                out.close()
                socket.close()
                onSuccess()
            } catch (e: Exception) {
                Log.e("IMPRESORA", "Error en imprimir()", e)
                onError(e)
            }
        }.start()
    }

    /**
     * Prints a plain CP850-encoded text block followed by a paper cut.
     * No ESC/POS formatting headers — the caller controls the full layout.
     * Used for the cuenta (bill) ticket where we build the entire text ourselves.
     */
    fun imprimirTextoPlano(
        texto: String,
        onSuccess: () -> Unit = {},
        onError: (Exception) -> Unit = {}
    ) {
        Thread {
            try {
                val socket = Socket(IP, PUERTO)
                val out: OutputStream = socket.getOutputStream()

                // Reset printer to a clean state
                out.write(ESC_RESET)

                // Replace the bullet character used elsewhere with the CP850 middle dot
                val textoFiltrado = texto.replace("•", "·")

                // Encode in CP850 — correctly handles tildes (á é í ó ú), ñ, ×, ·, etc.
                out.write(textoFiltrado.toByteArray(charsetCp850))

                out.write(PAPER_CUT)
                out.flush()
                out.close()
                socket.close()

                onSuccess()
            } catch (e: Exception) {
                Log.e("IMPRESORA", "Error en imprimirTextoPlano()", e)
                onError(e)
            }
        }.start()
    }

    /**
     * Prints an order comanda or update ticket.
     *
     * Layout:
     *   [Giant mesa number, centered]
     *   [Optional update icon if esActualizacion = true]
     *   --------------------------------
     *   [cuerpoTexto at tamanoCuerpo size]
     *
     * @param numeroMesa      Table number shown at the top in giant font.
     * @param esActualizacion True  → prepend "<<" before the number (update indicator).
     *                        False → print the number alone (first send).
     * @param cuerpoTexto     Pre-formatted body (items, deltas, etc.).
     * @param tamanoCuerpo    FONT_SIZE_MEDIUM (normal) or FONT_SIZE_LARGE (larger body).
     */
    fun imprimirPedido(
        numeroMesa: Int,
        esActualizacion: Boolean,
        cuerpoTexto: String,
        tamanoCuerpo: Int = FONT_SIZE_MEDIUM,
        onSuccess: () -> Unit = {},
        onError: (Exception) -> Unit = {}
    ) {
        Thread {
            try {
                val socket = Socket(IP, PUERTO)
                val out: OutputStream = socket.getOutputStream()

                out.write(ESC_RESET)

                // ── Giant mesa number ──
                out.write(ESC_CENTER)
                out.write(FONT_GIANT_MESA)   // size from BYTE_FUENTE_MESA constant
                out.write(ESC_BOLD_ON)

                val mesaLinea = if (esActualizacion) "<< $numeroMesa >>" else "$numeroMesa"
                out.write(mesaLinea.toByteArray(charsetCp850))
                out.write("\n\n".toByteArray(charsetCp850))

                out.write(ESC_BOLD_OFF)
                out.write(FONT_NORMAL)        // reset to 1× before separator

                // ── Separator ──
                out.write(ESC_LEFT)
                out.write("--------------------------------\n\n".toByteArray(charsetCp850))

                // ── Body ──
                out.write(FONT_CUERPO_PEDIDO) // size from BYTE_FUENTE_CUERPO constant

                val cuerpoFiltrado = cuerpoTexto.replace("•", "·")
                out.write(cuerpoFiltrado.toByteArray(charsetCp850))

                out.write(FONT_NORMAL)        // reset after body

                // Feed + cut
                out.write("\n\n\n\n".toByteArray(charsetCp850))
                out.write(PAPER_CUT)
                out.flush()
                out.close()
                socket.close()

                onSuccess()
            } catch (e: Exception) {
                Log.e("IMPRESORA", "Error en imprimirPedido()", e)
                onError(e)
            }
        }.start()
    }
}