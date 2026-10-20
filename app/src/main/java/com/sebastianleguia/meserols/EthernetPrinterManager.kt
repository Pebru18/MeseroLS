package com.sebastianleguia.meserols

import android.util.Log
import java.io.OutputStream
import java.net.Socket
import java.nio.charset.Charset

object EthernetPrinterManager {

    // printer ip/port come from local.properties (see local.properties.example),
    // so the restaurant's network setup never ends up in version control
    private val IP = BuildConfig.PRINTER_IP
    private val PUERTO = BuildConfig.PRINTER_PORT

    const val FONT_SIZE_SMALL = 0
    const val FONT_SIZE_MEDIUM = 1
    const val FONT_SIZE_LARGE = 2

    // table number, giant font at the top of the ticket (GS ! n command)
    // 0x22 = 3x, 0x33 = 4x, 0x44 = 5x (both height and width)
    // "<< 12 >>" is 9 chars, 4x uses 36 of 42 chars available on the line so it fits.
    // 5x would need 45 and wrap the last ">" onto its own line, so don't go past 0x33
    // while the mesa line still uses the "<< n >>" format
    private const val BYTE_FUENTE_MESA: Byte = 0x33

    // order body (dish lines, quantities, update deltas)
    // 0x00 normal, 0x10 taller only (recommended, more chars per line),
    // 0x11 taller + wider, 0x20 much taller, 0x22 taller + wider (fewer chars per line)
    private const val BYTE_FUENTE_CUERPO: Byte = 0x01

    private val ESC_RESET = byteArrayOf(0x1B, 0x40)
    private val ESC_CENTER = byteArrayOf(0x1B, 0x61, 0x01)
    private val ESC_LEFT = byteArrayOf(0x1B, 0x61, 0x00)
    private val ESC_BOLD_ON = byteArrayOf(0x1B, 0x45, 0x01)
    private val ESC_BOLD_OFF = byteArrayOf(0x1B, 0x45, 0x00)
    private val PAPER_CUT = byteArrayOf(0x1D, 0x56, 0x41, 0x10)

    private fun fontSizeCmd(size: Int): ByteArray = when (size) {
        FONT_SIZE_LARGE -> byteArrayOf(0x1D, 0x21, 0x11)
        FONT_SIZE_MEDIUM -> byteArrayOf(0x1D, 0x21, 0x00)
        else -> byteArrayOf(0x1D, 0x21, 0x00)
    }

    private val FONT_GIANT_MESA = byteArrayOf(0x1D, 0x21, BYTE_FUENTE_MESA)
    private val FONT_NORMAL = byteArrayOf(0x1D, 0x21, 0x00)
    private val FONT_CUERPO_PEDIDO = byteArrayOf(0x1D, 0x21, BYTE_FUENTE_CUERPO)

    private val charsetCp850: Charset = Charset.forName("Cp850")

    // plain utf-8 send + paper cut, kept around for simple cases that don't need ESC/POS formatting
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

    // prints a plain cp850 text block with no ESC/POS headers, the caller already
    // built the full layout. used for the cuenta (bill) ticket
    fun imprimirTextoPlano(
        texto: String,
        onSuccess: () -> Unit = {},
        onError: (Exception) -> Unit = {}
    ) {
        Thread {
            try {
                val socket = Socket(IP, PUERTO)
                val out: OutputStream = socket.getOutputStream()

                out.write(ESC_RESET)

                // swap the bullet for the cp850 middle dot
                val textoFiltrado = texto.replace("•", "·")

                // cp850 handles tildes (á é í ó ú), ñ, ×, · properly
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

    // prints an order comanda or update ticket: giant mesa number, optional "<<" update
    // marker, separator line, then the body at tamanoCuerpo size
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

                out.write(ESC_CENTER)
                out.write(FONT_GIANT_MESA)
                out.write(ESC_BOLD_ON)

                val mesaLinea = if (esActualizacion) "<< $numeroMesa >>" else "$numeroMesa"
                out.write(mesaLinea.toByteArray(charsetCp850))
                out.write("\n\n".toByteArray(charsetCp850))

                out.write(ESC_BOLD_OFF)
                out.write(FONT_NORMAL)

                out.write(ESC_LEFT)
                out.write("--------------------------------\n\n".toByteArray(charsetCp850))

                out.write(FONT_CUERPO_PEDIDO)

                val cuerpoFiltrado = cuerpoTexto.replace("•", "·")
                out.write(cuerpoFiltrado.toByteArray(charsetCp850))

                out.write(FONT_NORMAL)

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
