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

    // inline markers the ticket builders put in the body text, the printer code turns them
    // into size changes and they never reach the paper
    const val MARCA_CANT_ON = '\u0001'
    const val MARCA_CANT_OFF = '\u0002'
    const val MARCA_ACT_ON = '\u0003'
    const val MARCA_ACT_OFF = '\u0004'
    const val MARCA_ACT_ETIQUETA = '\u0005'

    // quantity: wider, same height as the body (GS ! n -> 0x11 = 2x wide, 2x tall)
    private val FONT_CANTIDAD = byteArrayOf(0x1D, 0x21, 0x11)

    // update rule: ESC 3 n sets the line spacing, ESC 2 goes back to the default
    // lower the 18 to pack the rule tighter (it may overlap its neighbours), raise it if the label gets cut
    private val LINE_SPACING_TIGHT = byteArrayOf(0x1B, 0x33, 18)
    private val LINE_SPACING_DEFAULT = byteArrayOf(0x1B, 0x32)

    // characters per printed line at normal size (the bill uses the same 48)
    // if a rule wraps onto a second line lower it, if it stops short of the right edge raise it
    private const val ANCHO_TICKET = 48

    // "ACT n" label at the end of the update rule: 0x00 normal, 0x10 wider, 0x01 taller, 0x11 both
    private const val BYTE_FUENTE_ETIQUETA: Byte = 0x10
    private val FONT_ETIQUETA_ACT = byteArrayOf(0x1D, 0x21, BYTE_FUENTE_ETIQUETA)
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
    // full-width "-----------ACT 1" rule: thin dashes with the label wider at the right end
    fun lineaActualizacion(numero: Int): String {
        val etiqueta = "ACT $numero"
        val factorAncho = ((BYTE_FUENTE_ETIQUETA.toInt() shr 4) and 0x0F) + 1
        val guiones = maxOf(ANCHO_TICKET - etiqueta.length * factorAncho, 0)

        return "$MARCA_ACT_ON${"-".repeat(guiones)}$MARCA_ACT_ETIQUETA$etiqueta\n$MARCA_ACT_OFF"
    }
    // writes the body, switching the printer size at each marker
    private fun escribirCuerpo(out: OutputStream, cuerpo: String) {
        val pendiente = StringBuilder()

        fun volcar() {
            if (pendiente.isNotEmpty()) {
                out.write(pendiente.toString().toByteArray(charsetCp850))
                pendiente.setLength(0)
            }
        }

        for (c in cuerpo) {
            when (c) {
                MARCA_CANT_ON -> {
                    volcar()
                    out.write(FONT_CANTIDAD)
                }
                MARCA_CANT_OFF -> {
                    volcar()
                    out.write(FONT_CUERPO_PEDIDO)
                }
                MARCA_ACT_ON -> {
                    volcar()
                    out.write(FONT_NORMAL)
                    out.write(LINE_SPACING_TIGHT)
                }
                MARCA_ACT_ETIQUETA -> {
                    volcar()
                    out.write(FONT_ETIQUETA_ACT)
                }
                MARCA_ACT_OFF -> {
                    volcar()
                    out.write(LINE_SPACING_DEFAULT)
                    out.write(FONT_CUERPO_PEDIDO)
                }
                else -> pendiente.append(c)
            }
        }
        volcar()
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
                out.write(("-".repeat(ANCHO_TICKET) + "\n\n").toByteArray(charsetCp850))

                out.write(FONT_CUERPO_PEDIDO)

                val cuerpoFiltrado = cuerpoTexto.replace("•", "·")
                escribirCuerpo(out, cuerpoFiltrado)

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
