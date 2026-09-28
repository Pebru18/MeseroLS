package com.example.meserols

import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.FirebaseFirestore
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CuentaActivity : AppCompatActivity() {

    private var totalFinal = 0.0
    private val db = FirebaseFirestore.getInstance()
    private var pedidoId: String? = null
    private var numeroMesa: Int = 0

    private lateinit var contenedor: LinearLayout
    private lateinit var btnConfirmarPago: Button

    // One entry per printable line on the ticket (populated by procesarCuenta).
    // precioUnitario is the per-unit price (what Format 2 shows in the PRECIO column).
    data class LineaTicket(val cantidad: Int, val nombre: String, val precioUnitario: Double)
    private val lineasParaImprimir = mutableListOf<LineaTicket>()
    // 🔥 NUEVA LISTA: Guardará el detalle visual y el grupo para el historial
    private val lineasHistorial = mutableListOf<Map<String, Any>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cuenta)
        val root = findViewById<LinearLayout>(R.id.rootLayout)

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        pedidoId   = intent.getStringExtra("pedido_id")
        numeroMesa = intent.getIntExtra("numero_mesa", 0)
        contenedor = findViewById(R.id.contenedorCuenta)
        btnConfirmarPago = findViewById(R.id.btnConfirmarPago)

        btnConfirmarPago.setOnClickListener {
            // Agregamos "Compuesto" al arreglo
            val opciones = arrayOf("Efectivo", "POS", "Plin/Yape", "Compuesto")
            var opcionSeleccionada = -1

            val builder = AlertDialog.Builder(this)
                .setTitle("¿Confirmar pago? Seleccione método:")
                .setNegativeButton("No", null)

            builder.setSingleChoiceItems(opciones, -1) { dialogInterface, which ->
                opcionSeleccionada = which
                val alertDialog = dialogInterface as AlertDialog
                alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
            }

            builder.setPositiveButton("Sí") { _, _ ->
                if (opcionSeleccionada != -1) {
                    val opcion = opciones[opcionSeleccionada]
                    if (opcion == "Compuesto") {
                        mostrarDialogoPagoCompuesto()
                    } else {
                        // 🔥 NUEVO: Creamos el desglose para pagos simples
                        val desglose = when (opcion) {
                            "Efectivo" -> mapOf("efectivo" to totalFinal, "pos" to 0.0, "yape" to 0.0)
                            "POS" -> mapOf("efectivo" to 0.0, "pos" to totalFinal, "yape" to 0.0)
                            "Plin/Yape" -> mapOf("efectivo" to 0.0, "pos" to 0.0, "yape" to totalFinal)
                            else -> mapOf("efectivo" to 0.0, "pos" to 0.0, "yape" to 0.0)
                        }
                        confirmarPago(opcion, desglose)
                    }
                }
            }

            val dialog = builder.create()
            dialog.show()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
        }

        cargarCuenta()
    }
    private fun mostrarDialogoPagoCompuesto() {
        val view = layoutInflater.inflate(R.layout.dialog_pago_compuesto, null)
        val tvTotal = view.findViewById<TextView>(R.id.tvTotalPagar)
        val etEfectivo = view.findViewById<EditText>(R.id.etEfectivo)
        val etPos = view.findViewById<EditText>(R.id.etPos)
        val etYape = view.findViewById<EditText>(R.id.etYape)
        val tvAsignado = view.findViewById<TextView>(R.id.tvAsignado)
        val tvPendiente = view.findViewById<TextView>(R.id.tvPendiente)

        tvTotal.text = "Total a pagar: S/ %.2f".format(totalFinal)
        tvPendiente.text = "Pendiente: S/ %.2f".format(totalFinal)

        val builder = AlertDialog.Builder(this)
            .setTitle("Detalle del Pago Compuesto")
            .setView(view)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Confirmar", null) // Lo sobreescribimos abajo para evitar que se cierre

        val dialog = builder.create()
        dialog.show()

        val btnConfirmar = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        btnConfirmar.isEnabled = false // Deshabilitado por defecto

        // Función que calcula la suma en tiempo real
        val calcularTotales = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                // Reemplazamos coma por punto por si el teclado está en español
                val efectivo = etEfectivo.text.toString().replace(",", ".").toDoubleOrNull() ?: 0.0
                val pos = etPos.text.toString().replace(",", ".").toDoubleOrNull() ?: 0.0
                val yape = etYape.text.toString().replace(",", ".").toDoubleOrNull() ?: 0.0

                val asignado = efectivo + pos + yape
                val pendiente = totalFinal - asignado

                tvAsignado.text = "Asignado: S/ %.2f".format(asignado)

                if (pendiente > 0) {
                    tvPendiente.text = "Pendiente: S/ %.2f".format(pendiente)
                    tvPendiente.setTextColor(getColor(android.R.color.holo_red_dark))
                } else if (pendiente < 0) {
                    tvPendiente.text = "Exceso: S/ %.2f".format(kotlin.math.abs(pendiente))
                    tvPendiente.setTextColor(getColor(android.R.color.holo_orange_dark))
                } else {
                    tvPendiente.text = "Pendiente: S/ 0.00 (✓ Cuadra Exacto)"
                    tvPendiente.setTextColor(getColor(android.R.color.holo_green_dark))
                }

                // Habilitamos el botón SOLO si la diferencia es casi nula (manejando decimales dobles)
                btnConfirmar.isEnabled = kotlin.math.abs(asignado - totalFinal) < 0.01
            }
        }

        etEfectivo.addTextChangedListener(calcularTotales)
        etPos.addTextChangedListener(calcularTotales)
        etYape.addTextChangedListener(calcularTotales)

        // Logica al presionar Confirmar
        btnConfirmar.setOnClickListener {
            val efectivo = etEfectivo.text.toString().replace(",", ".").toDoubleOrNull() ?: 0.0
            val pos = etPos.text.toString().replace(",", ".").toDoubleOrNull() ?: 0.0
            val yape = etYape.text.toString().replace(",", ".").toDoubleOrNull() ?: 0.0

            val partes = mutableListOf<String>()
            if (efectivo > 0) partes.add("Efe: S/%.2f".format(efectivo))
            if (pos > 0) partes.add("POS: S/%.2f".format(pos))
            if (yape > 0) partes.add("Y/P: S/%.2f".format(yape))

            // Esto creará un texto como: "Efe: S/20.00 | Y/P: S/30.00"
            val metodoString = partes.joinToString(" | ")
            val desglose = mapOf("efectivo" to efectivo, "pos" to pos, "yape" to yape)
            confirmarPago(metodoString, desglose)
            dialog.dismiss()
        }
    }
    private fun cargarCuenta() {
        val id = pedidoId ?: return

        db.collection("detalle_pedido")
            .whereEqualTo("pedido_id", id)
            .get()
            .addOnSuccessListener { result ->

                contenedor.removeAllViews()
                totalFinal = 0.0
                lineasParaImprimir.clear()
                lineasHistorial.clear() // 🔥 NUEVO: Limpiar para evitar duplicados al recargar

                val entradas               = mutableListOf<Int>()
                val segundos               = mutableListOf<Int>()
                val preciosSegundos        = mutableListOf<Double>()

                val entradasMarinas        = mutableListOf<Int>()
                val segundosMarinos        = mutableListOf<Int>()
                val preciosSegundosMarinos = mutableListOf<Double>()

                // Stores one Pair<nombre, precioPorUnidad> per ordered unit.
                // Negative precioPorUnidad = cancelled unit. Grouped in procesarCuenta.
                val otros = mutableListOf<Triple<String, Double, String>>()

                var pendientes = result.size()

                if (pendientes == 0) {
                    mostrarLinea("TOTAL", 0.0)
                    return@addOnSuccessListener
                }

                fun procesarCuenta(
                    entradas: List<Int>,
                    segundos: List<Int>,
                    preciosSegundos: List<Double>,
                    entradasMarinas: List<Int>,
                    segundosMarinos: List<Int>,
                    preciosSegundosMarinos: List<Double>,
                    otros: List<Triple<String, Double, String>>
                ) {
                    // ── MENÚ CRIOLLO ──────────────────────────────────────────
                    val entradaPrecio = 4.0

                    val entradasPos      = entradas.count { it > 0 }
                    val entradasNeg      = entradas.count { it < 0 }
                    val cantidadEntradas = (entradasPos - entradasNeg).coerceAtLeast(0)

                    val segundosPos      = segundos.count { it > 0 }
                    val segundosNeg      = segundos.count { it < 0 }
                    val cantidadSegundos = (segundosPos - segundosNeg).coerceAtLeast(0)

                    val cantidadMenus    = minOf(cantidadEntradas, cantidadSegundos)
                    val preciosOrdenados = preciosSegundos.sorted()
                    val menusPrecio      = preciosOrdenados.take(cantidadMenus).sum()

                    val entradasSobrantes         = cantidadEntradas - cantidadMenus
                    val segundosSobrantesCantidad = cantidadSegundos - cantidadMenus
                    val segundosSobrantes = preciosOrdenados
                        .drop(cantidadMenus)
                        .take(segundosSobrantesCantidad)

                    if (cantidadMenus > 0) {
                        mostrarLinea("Menú x$cantidadMenus", menusPrecio)
                        lineasHistorial.add(mapOf("descripcion" to "Menú x$cantidadMenus", "total" to menusPrecio, "grupo" to "Menú Criollo"))
                        totalFinal += menusPrecio
                        // Ticket: group by price so menus with different prices
                        // appear as separate lines (e.g. "1 Menu Criollo S/12.00"
                        // + "1 Menu Criollo S/15.00") instead of a single averaged
                        // line ("2 Menu Criollo S/13.50").
                        preciosOrdenados.take(cantidadMenus)
                            .groupingBy { it }.eachCount()
                            .entries.sortedBy { it.key }
                            .forEach { (precio, count) ->
                                lineasParaImprimir.add(LineaTicket(count, "Menu Criollo", precio))
                            }
                    }
                    if (entradasSobrantes > 0) {
                        val totalEntradas = entradasSobrantes * entradaPrecio
                        mostrarLinea("Entrada x$entradasSobrantes", totalEntradas)
                        lineasHistorial.add(mapOf("descripcion" to "Entrada x$entradasSobrantes", "total" to totalEntradas, "grupo" to "Entrada"))
                        lineasParaImprimir.add(LineaTicket(entradasSobrantes, "Entrada", entradaPrecio))
                        totalFinal += totalEntradas
                    }
                    if (segundosSobrantes.isNotEmpty()) {
                        val totalSegundos = segundosSobrantes.sum() - (1 * segundosSobrantes.size)
//ACÁ SE CAMBIAN LOS PRECIOS DE DESCUENTO A SEGUNDO SOLO
                        val precioUnit = if (segundosSobrantes.isNotEmpty())
                            totalSegundos / segundosSobrantes.size else 0.0
                        mostrarLinea("Segundo x${segundosSobrantes.size}", totalSegundos)
                        lineasHistorial.add(mapOf("descripcion" to "Segundo x${segundosSobrantes.size}", "total" to totalSegundos, "grupo" to "Segundo"))
                        lineasParaImprimir.add(LineaTicket(segundosSobrantes.size, "Segundo", precioUnit))
                        totalFinal += totalSegundos
                    }

                    // ── MENÚ MARINO ───────────────────────────────────────────
                    val entradaMarinaPrecio = 10.0

                    val entradasMarinasPos  = entradasMarinas.count { it > 0 }
                    val entradasMarinasNeg  = entradasMarinas.count { it < 0 }
                    val cantidadEntradasMarinas = (entradasMarinasPos - entradasMarinasNeg).coerceAtLeast(0)

                    val segundosMarinosPos  = segundosMarinos.count { it > 0 }
                    val segundosMarinosNeg  = segundosMarinos.count { it < 0 }
                    val cantidadSegundosMarinos = (segundosMarinosPos - segundosMarinosNeg).coerceAtLeast(0)

                    val cantidadMenusMarinos    = minOf(cantidadEntradasMarinas, cantidadSegundosMarinos)
                    val preciosMarinosOrdenados = preciosSegundosMarinos.sorted()
                    val menusMarinosPrecio      = preciosMarinosOrdenados.take(cantidadMenusMarinos).sum()

                    val entradasMarinasSobrantes         = cantidadEntradasMarinas - cantidadMenusMarinos
                    val segundosMarinosSobrantesCantidad = cantidadSegundosMarinos - cantidadMenusMarinos
                    val segundosMarinosSobrantes = preciosMarinosOrdenados
                        .drop(cantidadMenusMarinos)
                        .take(segundosMarinosSobrantesCantidad)

                    if (cantidadMenusMarinos > 0) {
                        mostrarLinea("Menú Marino x$cantidadMenusMarinos", menusMarinosPrecio)
                        lineasHistorial.add(mapOf("descripcion" to "Menú Marino x$cantidadMenusMarinos", "total" to menusMarinosPrecio, "grupo" to "Menú Marino"))
                        totalFinal += menusMarinosPrecio
                        // Same per-price grouping as Menú Criollo above.
                        preciosMarinosOrdenados.take(cantidadMenusMarinos)
                            .groupingBy { it }.eachCount()
                            .entries.sortedBy { it.key }
                            .forEach { (precio, count) ->
                                lineasParaImprimir.add(LineaTicket(count, "Menu Marino", precio))
                            }
                    }
                    if (entradasMarinasSobrantes > 0) {
                        val totalEntradasMarinas = entradasMarinasSobrantes * entradaMarinaPrecio
                        mostrarLinea("Entrada Marina x$entradasMarinasSobrantes", totalEntradasMarinas)
                        lineasHistorial.add(mapOf("descripcion" to "Entrada Marina x$entradasMarinasSobrantes", "total" to totalEntradasMarinas, "grupo" to "Entrada Marina"))
                        lineasParaImprimir.add(LineaTicket(entradasMarinasSobrantes, "Entrada Marina", entradaMarinaPrecio))
                        totalFinal += totalEntradasMarinas
                    }
                    if (segundosMarinosSobrantes.isNotEmpty()) {
                        val totalSegundosMarinos = segundosMarinosSobrantes.sum() - (5 * segundosMarinosSobrantes.size)
                        val precioUnit = if (segundosMarinosSobrantes.isNotEmpty())
                            totalSegundosMarinos / segundosMarinosSobrantes.size else 0.0
                        mostrarLinea("Segundo Marino x${segundosMarinosSobrantes.size}", totalSegundosMarinos)
                        lineasHistorial.add(mapOf("descripcion" to "Segundo Marino x${segundosMarinosSobrantes.size}", "total" to totalSegundosMarinos, "grupo" to "Segundo Marino"))
                        lineasParaImprimir.add(LineaTicket(segundosMarinosSobrantes.size, "Segundo Marino", precioUnit))
                        totalFinal += totalSegundosMarinos
                    }

                    // ── CARTA / BEBIDAS — grouped ─────────────────────────────
                    // Aggregate by nombre (full string, including option lines) so
                    // 3 identical items become one line instead of three.

                    data class OtroAgrupado(var unidades: Int, var totalPrecio: Double, var grupo: String)

                    val agrupados = linkedMapOf<String, OtroAgrupado>()

                    for ((nombre, precio, grupoItem) in otros) {
                        val entry = agrupados.getOrPut(nombre) { OtroAgrupado(0, 0.0, grupoItem) }
                        entry.totalPrecio += precio
                        if (precio >= 0) entry.unidades++ else entry.unidades--
                    }

                    for ((nombre, entry) in agrupados) {
                        if (entry.totalPrecio == 0.0) continue  // fully cancelled — skip

                        val nombreDisplay = nombre.split("\n").first()
                        val absUnidades   = kotlin.math.abs(entry.unidades)
                        val precioUnit    = if (absUnidades > 0) entry.totalPrecio / absUnidades else 0.0

                        val etiqueta = if (absUnidades > 1) "$nombreDisplay x$absUnidades"
                        else nombreDisplay

                        mostrarLinea(etiqueta, entry.totalPrecio)
                        lineasHistorial.add(mapOf("descripcion" to etiqueta, "total" to entry.totalPrecio, "grupo" to entry.grupo))
                        lineasParaImprimir.add(LineaTicket(absUnidades, nombreDisplay, precioUnit))
                        totalFinal += entry.totalPrecio
                    }

                    mostrarLinea("TOTAL", totalFinal)

                    // Print the ticket as soon as the cuenta screen is ready,
                    // but only when the waiter chose "Cuenta única".
                    // "Dividir cuentas" is handled manually by the waiter.
                    if (intent.getStringExtra("modo") == "unica") {
                        val textoTicket = construirTextoTicketCuenta()
                        EthernetPrinterManager.imprimirTextoPlano(
                            texto   = textoTicket,
                            onError = {
                                runOnUiThread {
                                    Toast.makeText(
                                        this,
                                        "Error al imprimir el ticket",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        )
                    }
                }

                for (doc in result) {
                    val nombre         = doc.getString("nombre") ?: ""
                    val cantidad       = (doc.getLong("cantidad") ?: 0).toInt()
                    val precioGuardado = (doc.getLong("precio") ?: 0L) / 100.0
                    val productoId     = doc.getString("producto_id") ?: ""
                    val anulado        = doc.getBoolean("anulado") ?: false

                    // Use grupo field as primary classifier (set since the grupo system
                    // was introduced). Fall back to nombre.contains() for older orders
                    // that pre-date the grupo field.
                    val grupo = doc.getString("grupo") ?: ""

                    val esEntradaMarina = grupo == "Entrada Marina"
                            || (grupo.isEmpty() && nombre.contains("Entrada Marina"))
                    val esSegundoMarino = grupo == "Segundo Marino"
                            || (grupo.isEmpty() && nombre.contains("Segundo Marino"))
                    val esEntrada       = grupo == "Entrada"
                            || (grupo.isEmpty() && nombre.contains("Entrada") && !esEntradaMarina)
                    val esSegundo       = grupo == "Segundo"
                            || (grupo.isEmpty() && nombre.contains("Segundo") && !esSegundoMarino)

                    if (anulado) {
                        pendientes--
                        if (pendientes == 0) procesarCuenta(entradas, segundos, preciosSegundos, entradasMarinas, segundosMarinos, preciosSegundosMarinos, otros)
                        continue
                    }

                    if (esEntradaMarina) {
                        repeat(kotlin.math.abs(cantidad)) { entradasMarinas.add(if (cantidad > 0) 1 else -1) }
                        pendientes--
                        if (pendientes == 0) procesarCuenta(entradas, segundos, preciosSegundos, entradasMarinas, segundosMarinos, preciosSegundosMarinos, otros)
                        continue
                    }

                    if (esEntrada) {
                        repeat(kotlin.math.abs(cantidad)) { entradas.add(if (cantidad > 0) 1 else -1) }
                        pendientes--
                        if (pendientes == 0) procesarCuenta(entradas, segundos, preciosSegundos, entradasMarinas, segundosMarinos, preciosSegundosMarinos, otros)
                        continue
                    }

                    if (esSegundoMarino) {
                        repeat(kotlin.math.abs(cantidad)) {
                            segundosMarinos.add(if (cantidad > 0) 1 else -1)
                            if (cantidad > 0) preciosSegundosMarinos.add(precioGuardado)
                        }
                        pendientes--
                        if (pendientes == 0) procesarCuenta(entradas, segundos, preciosSegundos, entradasMarinas, segundosMarinos, preciosSegundosMarinos, otros)
                        continue
                    }

                    if (esSegundo) {
                        repeat(kotlin.math.abs(cantidad)) {
                            segundos.add(if (cantidad > 0) 1 else -1)
                            if (cantidad > 0) preciosSegundos.add(precioGuardado)
                        }
                        pendientes--
                        if (pendientes == 0) procesarCuenta(entradas, segundos, preciosSegundos, entradasMarinas, segundosMarinos, preciosSegundosMarinos, otros)
                        continue
                    }

                    // ── Carta / Bebidas ─────────────────────────────────────────
                    // addOnFailureListener prevents a blank screen when any product
                    // lookup fails — pendientes still decrements so the rest renders.
                    db.collection("productos")
                        .document(productoId)
                        .get()
                        .addOnSuccessListener { prod ->
                            val precio = (prod.getDouble("precio") ?: 0.0) / 100
                            repeat(kotlin.math.abs(cantidad)) {
                                otros.add(Triple(nombre, if (cantidad > 0) precio else -precio, grupo))
                            }
                            pendientes--
                            if (pendientes == 0) procesarCuenta(entradas, segundos, preciosSegundos, entradasMarinas, segundosMarinos, preciosSegundosMarinos, otros)
                        }
                        .addOnFailureListener {
                            pendientes--
                            if (pendientes == 0) procesarCuenta(entradas, segundos, preciosSegundos, entradasMarinas, segundosMarinos, preciosSegundosMarinos, otros)
                        }
                }
            }
            .addOnFailureListener {
                Toast.makeText(this, "Error al cargar la cuenta", Toast.LENGTH_SHORT).show()
            }
    }

    private fun mostrarLinea(nombre: String, total: Double) {
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.HORIZONTAL
        layout.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        layout.setPadding(0, 8, 0, 8)

        val txtNombre = TextView(this)
        txtNombre.text = nombre
        txtNombre.textSize = if (nombre == "TOTAL") 20f else 16f
        txtNombre.setTextColor(getColor(android.R.color.black))
        txtNombre.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

        val txtPrecio = TextView(this)
        txtPrecio.text = "S/ %.2f".format(total)
        txtPrecio.textSize = if (nombre == "TOTAL") 20f else 16f
        txtPrecio.setTextColor(getColor(android.R.color.black))
        txtPrecio.textAlignment = TextView.TEXT_ALIGNMENT_VIEW_END

        layout.addView(txtNombre)
        layout.addView(txtPrecio)
        contenedor.addView(layout)
    }

    // ── TICKET CONSTRUCTION ───────────────────────────────────────────────────

    /**
     * Builds the Format 2 bill text for the thermal printer.
     *
     * Line width = 40 chars (safe for 80 mm paper at normal ESC/POS font).
     * Layout per item line: [CANT:4][space:1][DESCRIPCION:21][P.UNIT:8][IMPORTE:8] = 42
     *
     * Example output:
     *   ========================================
     *               LAS SIRENAS
     *                  Mesa 5
     *           25/07/2026 14:30
     *   ========================================
     *
     *   CANT DESCRIPCION         P.UNIT IMPORTE
     *   ----------------------------------------
     *      3 Menu Criollo        S/12.00 S/36.00
     *      1 Arroz con Pato      S/20.00 S/20.00
     *   ========================================
     *   TOTAL                           S/56.00
     *   ========================================
     *
     *          Gracias por su visita
     */
    // W = characters per line on 80 mm paper at default font.
    // 42 is the standard for most 80 mm ESC/POS printers. Increase to 48 if
    // you still see whitespace on the right after printing.
    private val W = 48

    private fun construirTextoTicketCuenta(): String {

        fun sep(c: Char) = c.toString().repeat(W)

        // Center a string inside a field of W characters.
        fun center(s: String): String {
            val t = s.take(W)
            return t.padStart((W + t.length) / 2).padEnd(W)
        }

        val fecha = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())

        val sb = StringBuilder()

        // ── Header ──
        sb.appendLine(sep('='))
        sb.appendLine(center("LAS SIRENAS"))
        if (numeroMesa > 0) sb.appendLine(center("Mesa $numeroMesa"))
        sb.appendLine(center(fecha))
        sb.appendLine(sep('='))
        sb.appendLine()

        // ── Column header ──
        // Layout: CANT(4) + " "(1) + DESCRIPCION(21) + P.UNIT(8) + IMPORTE(8) = 42
        val descW = W - 4 - 1 - 8 - 8  // = 21 at W=42
        sb.appendLine("${"CANT".padStart(4)} ${"DESCRIPCION".padEnd(descW)}${"P.UNIT".padStart(8)}${"IMPORTE".padStart(8)}")
        sb.appendLine(sep('-'))

        // ── Item lines ──
        for (linea in lineasParaImprimir) {
            val cant        = linea.cantidad.toString().padStart(4)
            val desc        = linea.nombre.take(descW).padEnd(descW)
            val precioUnit  = "S/%.2f".format(linea.precioUnitario).padStart(8)
            val precioTotal = "S/%.2f".format(linea.cantidad * linea.precioUnitario).padStart(8)
            sb.appendLine("$cant $desc$precioUnit$precioTotal")
        }

        // ── Total ──
        sb.appendLine(sep('='))
        // TOTAL label fills (W - 8) chars, price takes 8 chars → total = W
        sb.appendLine("${"TOTAL".padEnd(W - 8)}${"S/%.2f".format(totalFinal).padStart(8)}")
        sb.appendLine(sep('='))
        sb.appendLine()
        sb.appendLine(center("Gracias por su visita"))

        // Feed paper so the text clears the tear bar
        repeat(4) { sb.appendLine() }

        return sb.toString()
    }

    // ─────────────────────────────────────────────────────────────────────────

    private fun confirmarPago(metodoPago: String, desglose: Map<String, Double>) {
        val id = pedidoId ?: return
        val pedidoRef = db.collection("pedidos").document(id)
        val timestamp = System.currentTimeMillis()

        pedidoRef.get()
            .addOnSuccessListener { pedidoDoc ->
                if (!pedidoDoc.exists()) return@addOnSuccessListener
                val data = pedidoDoc.data ?: return@addOnSuccessListener
                // Pasamos el desglose
                guardarHistorial(data, timestamp, totalFinal, metodoPago, desglose)
            }
            .addOnFailureListener {
                Toast.makeText(this, "Error al confirmar el pago", Toast.LENGTH_SHORT).show()
            }
    }

    private fun guardarHistorial(
        data: Map<String, Any>,
        timestamp: Long,
        total: Double,
        metodoPago: String,
        desglose: Map<String, Double>
    ) {
        val id = pedidoId ?: return

        val historialData = data.toMutableMap()
        historialData["fecha_pago"]  = timestamp
        historialData["total"]       = total
        historialData["metodo_pago"] = metodoPago
        historialData["detalle_lineas"] = lineasHistorial
        historialData["desglose_pago"] = desglose

        db.collection("historial_dia")
            .document(id)
            .set(historialData)
            .addOnSuccessListener {

                db.collection("detalle_pedido")
                    .whereEqualTo("pedido_id", id)
                    .get()
                    .addOnSuccessListener { result ->

                        val batch    = db.batch()
                        val mesas    = data["mesas"] as? List<Long> ?: listOf()
                        val pedidoRef = db.collection("pedidos").document(id)

                        for (doc in result) {
                            batch.delete(doc.reference)
                        }

                        for (mesa in mesas) {
                            val mesaRef = db.collection("mesas").document(mesa.toString())
                            batch.update(mesaRef, mapOf("estado" to "libre", "pedido_id" to null))
                        }

                        batch.delete(pedidoRef)

                        // Delete the update-history document so actualizaciones_pedido
                        // doesn't accumulate orphan docs after orders are closed.
                        batch.delete(db.collection("actualizaciones_pedido").document(id))

                        batch.commit()
                            .addOnSuccessListener {
                                Toast.makeText(this, "Pago confirmado", Toast.LENGTH_SHORT).show()
                                val intent = Intent(this, MainActivity::class.java)
                                intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                                startActivity(intent)
                                finish()
                            }
                            .addOnFailureListener {
                                Toast.makeText(this, "Error al cerrar el pedido", Toast.LENGTH_SHORT).show()
                            }
                    }
                    .addOnFailureListener {
                        Toast.makeText(this, "Error al eliminar los detalles del pedido", Toast.LENGTH_SHORT).show()
                    }
            }
            .addOnFailureListener {
                Toast.makeText(this, "Error al guardar el historial", Toast.LENGTH_SHORT).show()
            }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}