package com.example.meserols

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.appcompat.app.AlertDialog
import android.widget.Toast

class HistorialActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private lateinit var contenedor: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_historial)
        val root = findViewById<LinearLayout>(R.id.rootLayout)

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        contenedor = findViewById(R.id.contenedorHistorial)

        cargarHistorial()
    }

    private fun cargarHistorial() {
        db.collection("historial_dia")
            .orderBy("fecha_pago", Query.Direction.DESCENDING)
            .get()
            .addOnSuccessListener { result ->

                contenedor.removeAllViews()

                // 1. Configurar el formato de fecha (Ej: "viernes 21, agosto 2026")
                val dateFormat = SimpleDateFormat("EEEE dd, MMMM yyyy", Locale("es", "ES"))

                // 2. Agrupar los documentos de Firebase por esta fecha
                val groupedDocs = result.documents.groupBy { doc ->
                    val fechaMs = doc.getLong("fecha_pago") ?: 0L
                    val formatted = dateFormat.format(Date(fechaMs))
                    // Convertimos la primera letra en mayúscula
                    formatted.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
                }

                // 3. Dibujar los grupos en pantalla
                for ((dateTitle, docs) in groupedDocs) {

                    // --- A. Dibujar el Título del Día ---
                    val titleView = TextView(this)
                    titleView.text = dateTitle
                    titleView.textSize = 18f
                    titleView.setTypeface(null, android.graphics.Typeface.BOLD)
                    titleView.setPadding(0, 48, 0, 16)
                    // Puedes cambiar el color si quieres que resalte más
                    titleView.setTextColor(getColor(android.R.color.holo_blue_dark))

                    titleView.setOnClickListener {
                        mostrarResumenDia(dateTitle, docs)
                    }
                    contenedor.addView(titleView)

                    // --- B. Dibujar los Pedidos de ese Día ---
                    for (doc in docs) {
                        val total = doc.getDouble("total") ?: 0.0
                        val fecha = doc.getLong("fecha_pago") ?: 0L
                        val mesas = doc.get("mesas") as? List<Long> ?: listOf()
                        val metodoPago = doc.getString("metodo_pago") ?: "No especificado"
                        val detalleLineas = doc.get("detalle_lineas") as? List<Map<String, Any>>

                        // Solo mostramos la hora para que la lista no sea redundante
                        val horaTexto = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(fecha))

                        val text = TextView(this)
                        text.text = "Mesa ${mesas.joinToString()} - S/ %.2f [%s] a las $horaTexto".format(total, metodoPago)
                        text.textSize = 16f
                        text.setPadding(32, 12, 0, 12) // Le damos sangría para que parezca una lista dentro del título
                        text.setTextColor(getColor(android.R.color.black))
                        text.setOnClickListener {
                            mostrarDialogoDetalle(mesas.joinToString(), total, metodoPago, detalleLineas)
                        }
                        contenedor.addView(text)
                    }
                }
            }
    }

    // 🔥 NUEVA FUNCIÓN: Calcula el resumen iterando los documentos y sus detalles
    private fun mostrarResumenDia(fechaTitulo: String, docs: List<com.google.firebase.firestore.DocumentSnapshot>) {
        var totalVenta = 0.0
        var totalEfectivo = 0.0
        var totalPos = 0.0
        var totalYape = 0.0
        var totalBebidas = 0.0

        for (doc in docs) {
            val totalDoc = doc.getDouble("total") ?: 0.0
            totalVenta += totalDoc

            // 1. Sumar desglose de pagos
            val desglose = doc.get("desglose_pago") as? Map<String, Number>
            if (desglose != null) {
                // Al leer de Firestore as? Number previene crashes entre Long y Double
                totalEfectivo += desglose["efectivo"]?.toDouble() ?: 0.0
                totalPos += desglose["pos"]?.toDouble() ?: 0.0
                totalYape += desglose["yape"]?.toDouble() ?: 0.0
            } else {
                // 🛡️ Salvavidas para los pedidos antiguos (anteriores a esta actualización)
                val metodo = doc.getString("metodo_pago") ?: ""
                when {
                    metodo.contains("Efectivo") && !metodo.contains("|") -> totalEfectivo += totalDoc
                    metodo.contains("POS") && !metodo.contains("|") -> totalPos += totalDoc
                    metodo.contains("Plin") && !metodo.contains("|") -> totalYape += totalDoc
                }
            }

            // 2. Sumar bebidas desde el detalle
            val detalleLineas = doc.get("detalle_lineas") as? List<Map<String, Any>>
            if (detalleLineas != null) {
                for (linea in detalleLineas) {
                    val grupo = linea["grupo"] as? String ?: ""
                    if (grupo == "bebidas") {
                        totalBebidas += (linea["total"] as? Double) ?: 0.0
                    }
                }
            }
        }

        // Construir el diálogo
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Resumen del día")

        val sb = java.lang.StringBuilder()
        sb.appendLine(fechaTitulo)
        sb.appendLine("--------------------------------")
        sb.appendLine("Venta Total: S/ %.2f".format(totalVenta))
        sb.appendLine()
        sb.appendLine("Efectivo: S/ %.2f".format(totalEfectivo))
        sb.appendLine("POS: S/ %.2f".format(totalPos))
        sb.appendLine("Yape/Plin: S/ %.2f".format(totalYape))
        sb.appendLine("--------------------------------")
        sb.appendLine("Solo Bebidas: S/ %.2f".format(totalBebidas))

        builder.setMessage(sb.toString())
        builder.setPositiveButton("Cerrar", null)
        builder.setNegativeButton("IMPRIMIR DETALLE") { _, _ ->
            generarTicketDetalle(fechaTitulo, docs)
        }
        builder.show()
    }
    // 🔥 NUEVA FUNCIÓN: Agrupa y formatea la lista para impresión
    private fun generarTicketDetalle(fechaTitulo: String, docs: List<com.google.firebase.firestore.DocumentSnapshot>) {
        val yapePlinList = mutableListOf<String>()
        val efectivoList = mutableListOf<String>()
        val posList = mutableListOf<String>()
        val mixtosOtrosList = mutableListOf<String>() // Para pagos divididos o no especificados

        // Clasificamos cada pedido en su lista correspondiente
        for (doc in docs) {
            val metodo = doc.getString("metodo_pago") ?: "Desconocido"
            val fecha = doc.getLong("fecha_pago") ?: 0L
            val hora = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(fecha))
            val mesas = doc.get("mesas") as? List<Long> ?: listOf()
            val total = doc.getDouble("total") ?: 0.0

            val strMesa = mesas.joinToString()
            // Formato: Mesa X - HH:mm - S/ Total
            val linea = "Mesa $strMesa - $hora - S/ %.2f".format(total)

            val mLower = metodo.lowercase()

            when {
                mLower.contains("|") -> mixtosOtrosList.add("$linea [$metodo]") // Pagos divididos
                mLower.contains("yape") || mLower.contains("plin") -> yapePlinList.add(linea)
                mLower.contains("efectivo") -> efectivoList.add(linea)
                mLower.contains("pos") || mLower.contains("tarjeta") -> posList.add(linea)
                else -> mixtosOtrosList.add("$linea [$metodo]") // Cualquier otro método no contemplado
            }
        }

        // Construimos el string final ordenado
        val sb = java.lang.StringBuilder()
        sb.appendLine("DETALLE DE VENTAS")
        sb.appendLine(fechaTitulo)
        sb.appendLine("--------------------------------")

        if (yapePlinList.isNotEmpty()) {
            sb.appendLine("--- YAPE / PLIN ---")
            yapePlinList.forEach { sb.appendLine(it) }
            sb.appendLine()
        }

        if (efectivoList.isNotEmpty()) {
            sb.appendLine("--- EFECTIVO ---")
            efectivoList.forEach { sb.appendLine(it) }
            sb.appendLine()
        }

        if (posList.isNotEmpty()) {
            sb.appendLine("--- POS ---")
            posList.forEach { sb.appendLine(it) }
            sb.appendLine()
        }

        if (mixtosOtrosList.isNotEmpty()) {
            sb.appendLine("--- MIXTOS / OTROS ---")
            mixtosOtrosList.forEach { sb.appendLine(it) }
            sb.appendLine()
        }
        sb.appendLine("--------------------------------")
        repeat(4) { sb.appendLine() }
        val textoImprimir = sb.toString()

        EthernetPrinterManager.imprimirTextoPlano(
            texto = textoImprimir,
            onError = {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "Error al imprimir el detalle del día",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Vista previa de impresión")
            .setMessage(textoImprimir)
            .setPositiveButton("OK", null)
            .show()
    }
    private fun mostrarDialogoDetalle(mesas: String, total: Double, metodoPago: String, detalleLineas: List<Map<String, Any>>?) {
        val builder = androidx.appcompat.app.AlertDialog.Builder(this)
        builder.setTitle("Detalle Mesa $mesas")

        if (detalleLineas.isNullOrEmpty()) {
            builder.setMessage("Detalle no disponible (pedido antiguo)\n\nTotal: S/ %.2f\nMétodo: %s".format(total, metodoPago))
        } else {
            val sb = StringBuilder()

            // Recorremos los ítems
            for (linea in detalleLineas) {
                val desc = linea["descripcion"] as? String ?: ""
                val tot = linea["total"] as? Double ?: 0.0
                // Nota: El 'grupo' está en la base de datos (linea["grupo"]), pero no lo imprimimos aquí según pediste.

                sb.appendLine(desc)
                sb.appendLine("   -> S/ %.2f".format(tot))
                sb.appendLine()
            }

            sb.appendLine("--------------------------------")
            sb.appendLine("TOTAL A PAGAR: S/ %.2f".format(total))
            sb.appendLine("MÉTODO DE PAGO: $metodoPago")

            builder.setMessage(sb.toString())
        }

        builder.setPositiveButton("Cerrar", null)
        builder.show()
    }
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}