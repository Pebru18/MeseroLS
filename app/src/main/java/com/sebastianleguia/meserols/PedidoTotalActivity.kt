package com.sebastianleguia.meserols

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.FirebaseFirestore
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.content.Intent
import androidx.appcompat.app.AlertDialog
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PedidoTotalActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private lateinit var btnImprimirCuenta: Button
    private lateinit var btnEnviarPedido: Button
    private lateinit var btnSeguirPidiendo: Button
    private lateinit var btnVerCuenta: Button
    private lateinit var btnCancelarEdicion: Button
    private var pedidoId: String? = null
    private var numeroMesa: Int = 0
    private var yaAbrioCuenta = false
    private var estadoActual = "activo"
    private var usandoTemporal = false
    private var creandoTemporal = false
    private lateinit var lista: ListView
    private val items = mutableListOf<ItemPedido>()

    // canonical group order, used for the on-screen list and the print order (first send + updates)
    // groups not in this map fall to position 99, after "otros"
    private val ORDEN_GRUPOS = mapOf(
        "Entrada Marina" to 0,
        "Segundo Marino"  to 1,
        "Entrada"         to 2,
        "Segundo"         to 3,
        "Carta"           to 4,
        "Bebidas"         to 5,
        "Otros"           to 6
    )
    // total width of the "-----ACT 1-----" line on the printed ticket
    private val ANCHO_SEPARADOR = 40
    private fun verificarCambiosPendientes() {
        db.collection("detalle_pedido")
            .whereEqualTo("pedido_id", pedidoId)
            .whereEqualTo("enviado", false)
            .get()
            .addOnSuccessListener { result ->
                val hayCambios = result.isEmpty.not()
                btnImprimirCuenta.isEnabled = !hayCambios
            }
    }

    private fun actualizarBotonesSegunEstado() {
        db.collection("pedidos")
            .document(pedidoId!!)
            .get()
            .addOnSuccessListener { doc ->
                estadoActual = doc.getString("estado") ?: "activo"
                btnEnviarPedido.isEnabled = true
                val editando = doc.getBoolean("editando") ?: false
                btnEnviarPedido.visibility = Button.GONE
                btnImprimirCuenta.visibility = Button.GONE
                btnSeguirPidiendo.visibility = Button.GONE
                btnVerCuenta.visibility = Button.GONE
                btnCancelarEdicion.visibility = Button.GONE
                when (estadoActual) {
                    "activo" -> {
                        btnEnviarPedido.visibility = Button.VISIBLE
                    }
                    "enviado" -> {
                        if (editando) {
                            btnEnviarPedido.visibility = Button.VISIBLE
                            btnEnviarPedido.text = "ACTUALIZAR PEDIDO"
                            btnCancelarEdicion.visibility = Button.VISIBLE
                        } else {
                            btnImprimirCuenta.visibility = Button.VISIBLE
                        }
                    }
                    "cuenta" -> {
                        btnSeguirPidiendo.visibility = Button.VISIBLE
                        btnVerCuenta.visibility = Button.VISIBLE
                    }
                }
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pedido_total)
        pedidoId = intent.getStringExtra("pedido_id")
        numeroMesa = intent.getIntExtra("numero_mesa", 0)
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)

        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val root = findViewById<LinearLayout>(R.id.rootLayout)

        btnEnviarPedido    = findViewById(R.id.btnEnviarPedido)
        btnImprimirCuenta  = findViewById(R.id.btnImprimirCuenta)
        btnSeguirPidiendo  = findViewById(R.id.btnSeguirPidiendo)
        btnVerCuenta       = findViewById(R.id.btnVerCuenta)
        btnCancelarEdicion = findViewById(R.id.btnCancelarEdicion)

        pedidoId = intent.getStringExtra("pedido_id")

        btnSeguirPidiendo.setOnClickListener {
            db.collection("pedidos")
                .document(pedidoId!!)
                .update("estado", "enviado")

            // don't delete actualizaciones_pedido here, the full update history needs to
            // survive cuenta -> seguirPidiendo cycles so future update tickets keep the
            // right sequence number and the original order section stays correct

            Toast.makeText(this, "Volviendo a pedidos", Toast.LENGTH_SHORT).show()
            estadoActual = "enviado"
            actualizarBotonesSegunEstado()
            cargarPedido()
        }

        btnVerCuenta.setOnClickListener {
            if (yaAbrioCuenta) return@setOnClickListener
            yaAbrioCuenta = true
            val intent = Intent(this, CuentaActivity::class.java)
            intent.putExtra("pedido_id", pedidoId)
            startActivity(intent)
        }

        btnEnviarPedido.setOnClickListener {
            btnEnviarPedido.isEnabled = false
            // first send: estado activo, no temp collection involved yet
            if (!usandoTemporal) {
                db.collection("detalle_pedido")
                    .whereEqualTo("pedido_id", pedidoId)
                    .whereEqualTo("enviado", false)
                    .get()
                    .addOnSuccessListener { result ->
                        if (result.isEmpty) {
                            btnEnviarPedido.isEnabled = true
                            Toast.makeText(this, "No hay cambios", Toast.LENGTH_SHORT).show()
                            return@addOnSuccessListener
                        }
                        val batch = db.batch()
                        for (doc in result) {
                            batch.update(doc.reference, "enviado", true)
                        }
                        batch.commit().addOnSuccessListener {
                            db.collection("pedidos")
                                .document(pedidoId!!)
                                .update(
                                    mapOf(
                                        "estado"          to "enviado",
                                        "editando"        to false,
                                        "timestamp_envio" to System.currentTimeMillis()
                                    )
                                )

                            val texto = construirTextoPrimerPedido(result.documents)
                            EthernetPrinterManager.imprimirPedido(
                                numeroMesa    = numeroMesa,
                                esActualizacion = false,
                                cuerpoTexto   = texto,
                                tamanoCuerpo  = EthernetPrinterManager.FONT_SIZE_MEDIUM,
                                onSuccess = {},
                                onError   = {}
                            )

                            // store the original order for future update tickets, result.documents
                            // is the pre-commit snapshot so it still has the right data
                            val originalItems = aggregateDocsParaOriginal(result.documents)
                            db.collection("actualizaciones_pedido")
                                .document(pedidoId!!)
                                .set(mapOf(
                                    "original"  to originalItems,
                                    "historial" to emptyList<Any>()
                                ))

                            Toast.makeText(this, "Pedido enviado correctamente", Toast.LENGTH_SHORT).show()
                            actualizarBotonesSegunEstado()
                            verificarCambiosPendientes()
                            cargarPedido()
                        }
                    }

                return@setOnClickListener
            }

            // update send, usandoTemporal = true. flow:
            // 1. read temp docs (the new state after edits)
            // 2. read original detalle_pedido (state before this edit)
            // 3. read actualizaciones_pedido: stored original + previous update deltas
            // 4. compute this update's delta (structured item list)
            // 5. run the lifo removal-attribution algorithm across all sections
            // 6. build the full ticket body
            // 7. print
            // 8. batch: replace detalle_pedido with temp, delete temp
            // 9. on commit success: save new delta to actualizaciones_pedido
            db.collection("detalle_pedido_temp")
                .whereEqualTo("pedido_id", pedidoId)
                .get()
                .addOnSuccessListener { tempDocs ->

                    db.collection("detalle_pedido")
                        .whereEqualTo("pedido_id", pedidoId)
                        .get()
                        .addOnSuccessListener { originales ->

                            db.collection("actualizaciones_pedido")
                                .document(pedidoId!!)
                                .get()
                                .addOnSuccessListener { historialDoc ->

                                    @Suppress("UNCHECKED_CAST")
                                    val historialPrevio = (historialDoc.get("historial")
                                            as? List<Map<String, Any>>) ?: emptyList()

                                    // no stored original means seguirPidiendo cleared it (or it's the
                                    // first update ever), so derive it from the current detalle_pedido
                                    // state, before this edit gets committed
                                    @Suppress("UNCHECKED_CAST")
                                    val storedOriginal: List<Map<String, Any>> =
                                        (historialDoc.get("original") as? List<Map<String, Any>>)
                                            ?: aggregateDocsParaOriginal(originales.documents)

                                    val numeroActualizacion = historialPrevio.size + 1
                                    val hora = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

                                    // positive = added, negative = removed
                                    val deltaActual: List<Map<String, Any>> =
                                        computarDeltaItems(originales.documents, tempDocs.documents)

                                    // original-at-top format
                                    val cuerpoCompleto = construirTextoComandaConHistorial(
                                        original            = storedOriginal,
                                        historialPrevio     = historialPrevio,
                                        deltaActual         = deltaActual,
                                        horaActual          = hora,
                                        numeroActualizacion = numeroActualizacion
                                    )

                                    // async, doesn't block the batch below
                                    EthernetPrinterManager.imprimirPedido(
                                        numeroMesa      = numeroMesa,
                                        esActualizacion = true,
                                        cuerpoTexto     = cuerpoCompleto,
                                        tamanoCuerpo    = EthernetPrinterManager.FONT_SIZE_MEDIUM,
                                        onSuccess = {},
                                        onError   = {}
                                    )

                                    // delete originals, recreate from temp, delete temp
                                    val batch = db.batch()

                                    for (doc in originales) {
                                        batch.delete(doc.reference)
                                    }
                                    // docs of the same dish (positives and negatives) get summed, so
                                    // detalle_pedido keeps a single net doc per dish after the update
                                    val docsPorPlato = mutableMapOf<String, MutableList<DocumentSnapshot>>()

                                    for (doc in tempDocs) {
                                        val productoDoc   = doc.getString("producto_id") ?: ""
                                        val nombreDoc     = doc.getString("nombre") ?: ""
                                        val comentarioDoc = doc.getString("comentario") ?: ""
                                        val opcionesDoc   = doc.get("opciones") as? Map<String, String> ?: emptyMap()

                                        val clave = "$productoDoc|$nombreDoc|$comentarioDoc|${opcionesDoc.toSortedMap()}"

                                        docsPorPlato.getOrPut(clave) { mutableListOf() }.add(doc)
                                    }

                                    for (docsDelPlato in docsPorPlato.values) {
                                        val neto = docsDelPlato.sumOf { it.getLong("cantidad") ?: 0L }

                                        // a fully cancelled dish doesn't need to stay in the order
                                        if (neto <= 0L) continue

                                        // negative docs only carry the cancellation, so take the dish data from a positive one
                                        val base = docsDelPlato.firstOrNull { (it.getLong("cantidad") ?: 0L) > 0L }
                                            ?: docsDelPlato.first()

                                        val nuevo = hashMapOf(
                                            "pedido_id"   to pedidoId,
                                            "producto_id" to (base.getString("producto_id") ?: ""),
                                            "nombre"      to (base.getString("nombre") ?: ""),
                                            "grupo"       to (base.getString("grupo") ?: "Otros"),
                                            "cantidad"    to neto,
                                            "comentario"  to (base.getString("comentario") ?: ""),
                                            "opciones"    to (base.get("opciones") ?: emptyMap<String, String>()),
                                            "precio"      to (base.getLong("precio") ?: 0L),
                                            "enviado"     to true,
                                            "anulado"     to false
                                        )
                                        val ref = db.collection("detalle_pedido").document()
                                        batch.set(ref, nuevo)
                                    }
                                    for (doc in tempDocs) {
                                        batch.delete(doc.reference)
                                    }

                                    batch.commit().addOnSuccessListener {

                                        // structured data, not pre-rendered text, so future update
                                        // tickets can run the attribution algorithm again
                                        val nuevaEntrada = mapOf<String, Any>(
                                            "numero"    to numeroActualizacion.toLong(),
                                            "timestamp" to System.currentTimeMillis(),
                                            "hora"      to hora,
                                            "items"     to deltaActual
                                        )
                                        val historialActualizado = historialPrevio + listOf(nuevaEntrada)

                                        db.collection("actualizaciones_pedido")
                                            .document(pedidoId!!)
                                            .set(mapOf(
                                                "original"  to storedOriginal,
                                                "historial" to historialActualizado
                                            ))

                                        usandoTemporal = false

                                        db.collection("pedidos")
                                            .document(pedidoId!!)
                                            .update(
                                                mapOf(
                                                    "estado"          to "enviado",
                                                    "editando"        to false,
                                                    "timestamp_envio" to System.currentTimeMillis()
                                                )
                                            )

                                        Toast.makeText(this, "Pedido actualizado", Toast.LENGTH_SHORT).show()
                                        cargarPedido()
                                        actualizarBotonesSegunEstado()
                                        verificarCambiosPendientes()
                                    }
                                }
                        }
                }
        }

        btnCancelarEdicion.setOnClickListener {

            eliminarTemporal {

                db.collection("pedidos")
                    .document(pedidoId!!)
                    .update("editando", false)

                Toast.makeText(this, "Cambios cancelados", Toast.LENGTH_SHORT).show()

                cargarPedido()
                actualizarBotonesSegunEstado()
                verificarCambiosPendientes()
            }
        }

        btnImprimirCuenta.setOnClickListener {

            val opciones = arrayOf("Cuenta única", "Dividir cuentas")

            AlertDialog.Builder(this)
                .setTitle("¿Cómo desea la cuenta?")
                .setItems(opciones) { _, which ->

                    db.collection("pedidos")
                        .document(pedidoId!!)
                        .update("estado", "cuenta")

                    val intent = Intent(this, CuentaActivity::class.java)

                    intent.putExtra("pedido_id", pedidoId)
                    intent.putExtra("numero_mesa", numeroMesa)

                    if (which == 0) {
                        intent.putExtra("modo", "unica")
                    } else {
                        intent.putExtra("modo", "dividida")
                    }

                    startActivity(intent)
                }
                .show()
        }

        actualizarBotonesSegunEstado()

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->

            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            view.setPadding(0, bars.top, 0, bars.bottom)

            insets
        }

        lista = findViewById(R.id.listaPedido)

        cargarPedido()
    }

    // builds the ticket body for the first send (estado activo -> enviado)
    // items are sorted by ORDEN_GRUPOS then alphabetically, no group headers
    // called with the enviado=false snapshot, which on first send is everything
    private fun construirTextoPrimerPedido(docs: List<DocumentSnapshot>): String {

        data class Item(val grupo: String, val nombre: String, val comentario: String, var qty: Int)

        val mapa = mutableMapOf<String, Item>()

        for (doc in docs) {
            val nombre     = doc.getString("nombre") ?: ""
            val comentario = doc.getString("comentario") ?: ""
            val grupo      = doc.getString("grupo") ?: "Otros"
            val cantidad   = (doc.getLong("cantidad") ?: 0).toInt()
            if (cantidad <= 0) continue
            val key = "$nombre||$comentario"
            val existente = mapa[key]
            if (existente != null) existente.qty += cantidad
            else mapa[key] = Item(grupo, nombre, comentario, cantidad)
        }

        val ordenados = mapa.values.sortedWith(compareBy(
            { ORDEN_GRUPOS[it.grupo] ?: 99 },
            { it.nombre }
        ))

        val sb = StringBuilder()
        for (item in ordenados) {
            val lineas = item.nombre.split("\n")
            sb.append("· ${cantidadAncha(item.qty)} ${lineas[0]}\n")
            for (i in 1 until lineas.size) sb.append("   ${lineas[i]}\n")
            if (item.comentario.isNotBlank()) sb.append("   ** Nota: ${item.comentario}\n")
            sb.append("\n")
        }
        return sb.toString()
    }

    // aggregates DocumentSnapshots into the clean original-items format used for
    // storage in actualizaciones_pedido, only positive quantities are kept, items
    // with the same nombre+comentario get merged
    private fun aggregateDocsParaOriginal(docs: List<DocumentSnapshot>): List<Map<String, Any>> {
        data class Item(val grupo: String, val nombre: String, val comentario: String, var qty: Int)
        val mapa = mutableMapOf<String, Item>()
        for (doc in docs) {
            val nombre     = doc.getString("nombre") ?: ""
            val comentario = doc.getString("comentario") ?: ""
            val grupo      = doc.getString("grupo") ?: "Otros"
            val cantidad   = (doc.getLong("cantidad") ?: 0).toInt()
            // don't skip negative quantities here - after a committed update, detalle_pedido
            // can hold both a positive doc and a negative doc for the same item (e.g. qty 3
            // and qty -1 for the same Bistec), so summing everything and keeping only the
            // net-positive results is what gives the real quantity
            val key = "$nombre||$comentario"
            val existente = mapa[key]
            if (existente != null) existente.qty += cantidad
            else mapa[key] = Item(grupo, nombre, comentario, cantidad)
        }
        // only keeps items with a net-positive quantity, fully-cancelled items are excluded
        return mapa.values
            .filter { it.qty > 0 }
            .map { item ->
                mapOf<String, Any>(
                    "nombre"     to item.nombre,
                    "cantidad"   to item.qty.toLong(),
                    "comentario" to item.comentario,
                    "grupo"      to item.grupo
                )
            }
    }

    // computes the net delta between two detalle_pedido snapshots, returns a list where
    // cantidad > 0 means added and cantidad < 0 means removed. stored in historial so the
    // attribution algorithm can re-run on future tickets
    private fun computarDeltaItems(
        originales: List<DocumentSnapshot>,
        temporales: List<DocumentSnapshot>
    ): List<Map<String, Any>> {

        data class Item(val grupo: String, val nombre: String, val comentario: String, var qty: Int)

        fun agrupar(docs: List<DocumentSnapshot>): MutableMap<String, Item> {
            val mapa = mutableMapOf<String, Item>()
            for (doc in docs) {
                val nombre     = doc.getString("nombre") ?: ""
                val comentario = doc.getString("comentario") ?: ""
                val grupo      = doc.getString("grupo") ?: "Otros"
                val cantidad   = (doc.getLong("cantidad") ?: 0).toInt()
                val key = "$nombre||$comentario"
                val existente = mapa[key]
                if (existente != null) existente.qty += cantidad
                else mapa[key] = Item(grupo, nombre, comentario, cantidad)
            }
            return mapa
        }

        val mapaOrig = agrupar(originales)
        val mapaTemp = agrupar(temporales)
        val allKeys  = (mapaOrig.keys + mapaTemp.keys).toSet()

        val resultado = mutableListOf<Map<String, Any>>()
        for (key in allKeys) {
            val cantOrig  = mapaOrig[key]?.qty ?: 0
            val cantFinal = mapaTemp[key]?.qty ?: 0
            val delta = cantFinal - cantOrig
            if (delta == 0) continue
            val item = mapaTemp[key] ?: mapaOrig[key]!!
            resultado.add(mapOf(
                "nombre"     to item.nombre,
                "cantidad"   to delta.toLong(),   // positive = added, negative = removed
                "comentario" to item.comentario,
                "grupo"      to item.grupo
            ))
        }
        return resultado
    }

    // builds the full update ticket body. layout:
    //   [original order - the dishes as first sent, with struck-through lines
    //    showing any items removed by any update (including the current one)]
    //   --------------------------------
    //   ACTUALIZACION 1 - HH:mm
    //   [additions with + prefix]
    //   [dashes showing if anything added here was later removed]
    //   [removals in dashes directly]
    //   --------------------------------
    //   ACTUALIZACION 2 - HH:mm
    //   ...
    //   --------------------------------
    //   ACTUALIZACION N - HH:mm  <- current update
    //   ...
    //
    // attribution of removals uses a lifo algorithm: when a removal occurs, it's
    // attributed to the most recent source (the current update first, then older
    // updates, then original last) that still has available stock for that item
    private fun construirTextoComandaConHistorial(
        original: List<Map<String, Any>>,
        historialPrevio: List<Map<String, Any>>,
        deltaActual: List<Map<String, Any>>,
        horaActual: String,
        numeroActualizacion: Int
    ): String {

        // step 1: assemble all update entries in chronological order
        data class UpdateEntry(val numero: Int, val hora: String, val items: List<Map<String, Any>>)

        val todosLosDeltas: List<UpdateEntry> = historialPrevio.map { entry ->
            UpdateEntry(
                numero = ((entry["numero"] as? Long) ?: 0L).toInt(),
                hora   = (entry["hora"] as? String) ?: "",
                items  = @Suppress("UNCHECKED_CAST")
                (entry["items"] as? List<Map<String, Any>>) ?: emptyList()
            )
        } + listOf(UpdateEntry(numeroActualizacion, horaActual, deltaActual))

        // step 2: lifo removal-attribution algorithm
        //
        // stockByItem[key] = ordered list of StockEntry(source, availableQty)
        // a "source" is "original" or "update_N"
        // when a removal occurs, we deduct from the most recently added source that
        // still has available quantity, recording how much came from each source
        //
        // attributedRemovals[key][source] = total qty removed from that source
        // across all updates, including the current one

        data class StockEntry(val source: String, var qty: Int)

        val stockByItem         = mutableMapOf<String, MutableList<StockEntry>>()
        val attributedRemovals  = mutableMapOf<String, MutableMap<String, Int>>()

        // seed stock from original
        for (item in original) {
            val nombre     = item["nombre"] as? String ?: continue
            val comentario = item["comentario"] as? String ?: ""
            val qty        = ((item["cantidad"] as? Long) ?: 0L).toInt()
            if (qty <= 0) continue
            val key = "$nombre||$comentario"
            stockByItem.getOrPut(key) { mutableListOf() }.add(StockEntry("original", qty))
        }

        // process each update in order
        for (entry in todosLosDeltas) {
            val sourceId = "update_${entry.numero}"
            for (item in entry.items) {
                val nombre     = item["nombre"] as? String ?: continue
                val comentario = item["comentario"] as? String ?: ""
                val cantidad   = ((item["cantidad"] as? Long) ?: 0L).toInt()
                val key = "$nombre||$comentario"

                if (cantidad > 0) {
                    // addition: push new stock from this source
                    stockByItem.getOrPut(key) { mutableListOf() }
                        .add(StockEntry(sourceId, cantidad))
                } else if (cantidad < 0) {
                    // removal: deduct lifo, from the most-recent sources first
                    // when a dish gets cancelled, it logically cancels the most recently
                    // added version of it - this keeps the original order's items active
                    // and only strikes through update additions that got cancelled, which
                    // matches what the kitchen expects to read on the ticket
                    var toRemove = -cantidad
                    val stock    = stockByItem.getOrPut(key) { mutableListOf() }
                    val removals = attributedRemovals.getOrPut(key) { mutableMapOf() }
                    for (stockEntry in stock.reversed()) {
                        if (toRemove <= 0) break
                        val deduct = minOf(toRemove, stockEntry.qty)
                        stockEntry.qty  -= deduct
                        toRemove        -= deduct
                        removals[stockEntry.source] = (removals[stockEntry.source] ?: 0) + deduct
                    }
                }
            }
        }

        // step 3: build the print text

        val sb = StringBuilder()

        // original section
        data class OrigItem(val grupo: String, val nombre: String, val comentario: String, val qty: Int)

        val originalSorted = original
            .filter { ((it["cantidad"] as? Long) ?: 0L).toInt() > 0 }
            .map {
                OrigItem(
                    grupo      = it["grupo"] as? String ?: "Otros",
                    nombre     = it["nombre"] as? String ?: "",
                    comentario = it["comentario"] as? String ?: "",
                    qty        = ((it["cantidad"] as? Long) ?: 0L).toInt()
                )
            }
            .sortedWith(compareBy({ ORDEN_GRUPOS[it.grupo] ?: 99 }, { it.nombre }))

        for (item in originalSorted) {
            val key              = "${item.nombre}||${item.comentario}"
            val removedFromOrig  = attributedRemovals[key]?.get("original") ?: 0
            val netQty           = item.qty - removedFromOrig
            val lineas           = item.nombre.split("\n")

            if (netQty > 0) {
                sb.append("· ${cantidadAncha(netQty)} ${lineas[0]}\n")
                for (i in 1 until lineas.size) sb.append("   ${lineas[i]}\n")
                if (item.comentario.isNotBlank()) sb.append("   ** Nota: ${item.comentario}\n")
                // struck-through line right after, before the blank line
                if (removedFromOrig > 0) {
                    sb.append("${formatStrikethrough(removedFromOrig, lineas[0])}\n")
                }
                sb.append("\n")
            } else {
                // fully removed, only show the struck-through line
                if (removedFromOrig > 0) {
                    sb.append("${formatStrikethrough(removedFromOrig, lineas[0])}\n\n")
                }
            }
        }

        // update sections, one per entry in todosLosDeltas
        for (entry in todosLosDeltas) {
            val sourceId = "update_${entry.numero}"

            sb.append(separadorActualizacion(entry.numero))

            // sorted by ORDEN_GRUPOS then alphabetical, additions and removals mixed
            // together since that's how they're meant to read on the ticket
            data class DeltaItem(
                val grupo: String, val nombre: String, val comentario: String, val cantidad: Int
            )
            val itemsOrdenados = entry.items
                .map { item ->
                    DeltaItem(
                        grupo      = item["grupo"] as? String ?: "Otros",
                        nombre     = item["nombre"] as? String ?: "",
                        comentario = item["comentario"] as? String ?: "",
                        cantidad   = ((item["cantidad"] as? Long) ?: 0L).toInt()
                    )
                }
                .sortedWith(compareBy({ ORDEN_GRUPOS[it.grupo] ?: 99 }, { it.nombre }))

            for (item in itemsOrdenados) {
                val key    = "${item.nombre}||${item.comentario}"
                val lineas = item.nombre.split("\n")

                if (item.cantidad > 0) {
                    // for additions, work out how many of this batch got cancelled later
                    val removedFromThisSource = attributedRemovals[key]?.get(sourceId) ?: 0
                    val remaining = item.cantidad - removedFromThisSource

                    // only print the "+ n" line for what's actually still active - if remaining
                    // is 0 the whole addition got cancelled, so skip it and just show the
                    // struck-through marker instead of both "+1 pollo" and "xxxxx 1 pollo xxxxx"
                    if (remaining > 0) {
                        sb.append("+ ${cantidadAncha(remaining)} ${lineas[0]}\n")
                        for (i in 1 until lineas.size) sb.append("   ${lineas[i]}\n")
                        if (item.comentario.isNotBlank()) sb.append("   ** Nota: ${item.comentario}\n")
                    }
                    if (removedFromThisSource > 0) {
                        sb.append("${formatStrikethrough(removedFromThisSource, lineas[0])}\n")
                    }
                    sb.append("\n")
                } else if (item.cantidad < 0) {
                    // removal, shown directly as struck-through
                    val absQty = -item.cantidad
                    sb.append("${formatStrikethrough(absQty, lineas[0])}\n\n")
                }
            }
        }

        return sb.toString()
    }

    // marks a cancelled item for the printed ticket, "xxxxx n name xxxxx" - wrapped in
    // x's on both sides so it stands out from the "+" addition lines and the "----" separators
    private fun formatStrikethrough(qty: Int, nombre: String): String {
        return "XXXXX ${cantidadAncha(qty)} $nombre XXXXX"
    }

    // wraps a quantity in markers so the printer prints it wider than the dish name
    private fun cantidadAncha(qty: Int): String {
        return "${EthernetPrinterManager.MARCA_CANT_ON}$qty${EthernetPrinterManager.MARCA_CANT_OFF}"
    }

    // thin "-----ACT 1-----" line, printed small and with tight line spacing
    private fun separadorActualizacion(numero: Int): String {
        val etiqueta = "ACT $numero"
        val relleno = (ANCHO_SEPARADOR - etiqueta.length) / 2
        val linea = "-".repeat(relleno) + etiqueta + "-".repeat(ANCHO_SEPARADOR - etiqueta.length - relleno)
        return "${EthernetPrinterManager.MARCA_ACT_ON}$linea\n${EthernetPrinterManager.MARCA_ACT_OFF}"
    }
    fun obtenerColeccionActual(): String {
        if (estadoActual == "activo") return "detalle_pedido"
        return if (usandoTemporal) "detalle_pedido_temp" else "detalle_pedido"
    }

    private fun iniciarEdicionTemporal(callback: (() -> Unit)? = null) {

        if (usandoTemporal) {
            callback?.invoke()
            return
        }

        if (creandoTemporal) {
            return
        }

        db.collection("pedidos")
            .document(pedidoId!!)
            .get()
            .addOnSuccessListener { pedidoDoc ->

                if (pedidoDoc.getBoolean("editando") == true) {
                    usandoTemporal = true
                    callback?.invoke()
                } else {
                    clonarATemporal(callback)
                }
            }
    }
    private fun clonarATemporal(callback: (() -> Unit)? = null) {
        if (usandoTemporal) {
            callback?.invoke()
            return
        }

        if (creandoTemporal) {
            return
        }

        creandoTemporal = true

        db.collection("detalle_pedido")
            .whereEqualTo("pedido_id", pedidoId)
            .get()
            .addOnSuccessListener { result ->

                val batch = db.batch()

                for (doc in result) {

                    val nuevoDoc = hashMapOf(
                        "original_id"  to doc.id,
                        "pedido_id"    to pedidoId,
                        "producto_id"  to (doc.getString("producto_id") ?: ""),
                        "nombre"       to (doc.getString("nombre") ?: ""),
                        "grupo"        to (doc.getString("grupo") ?: "Otros"),
                        "cantidad"     to (doc.getLong("cantidad") ?: 0),
                        "comentario"   to (doc.getString("comentario") ?: ""),
                        "opciones"     to (doc.get("opciones") ?: emptyMap<String, String>()),
                        "precio"       to (doc.getLong("precio") ?: 0L),
                        "enviado"      to (doc.getBoolean("enviado") ?: true),
                        "anulado"      to (doc.getBoolean("anulado") ?: false)
                    )

                    val ref = db.collection("detalle_pedido_temp").document()

                    batch.set(ref, nuevoDoc)
                }

                batch.commit()
                    .addOnSuccessListener {

                        usandoTemporal  = true
                        creandoTemporal = false

                        db.collection("pedidos")
                            .document(pedidoId!!)
                            .update("editando", true)

                        callback?.invoke()
                    }
                    .addOnFailureListener {

                        creandoTemporal = false
                    }
            }
            .addOnFailureListener {

                creandoTemporal = false
            }
    }

    private fun eliminarTemporal(callback: (() -> Unit)? = null) {

        db.collection("detalle_pedido_temp")
            .whereEqualTo("pedido_id", pedidoId)
            .get()
            .addOnSuccessListener { result ->

                val batch = db.batch()

                for (doc in result) {
                    batch.delete(doc.reference)
                }

                batch.commit().addOnSuccessListener {

                    usandoTemporal = false
                    callback?.invoke()
                }
            }
    }

    private fun cargarPedido() {

        db.collection(obtenerColeccionActual())
            .whereEqualTo("pedido_id", pedidoId)
            .get()
            .addOnSuccessListener { result ->

                items.clear()

                val mapa = mutableMapOf<String, ItemPedido>()

                for (doc in result) {

                    val originalId = doc.getString("original_id")
                    val id         = doc.id

                    val productoId = doc.getString("producto_id") ?: ""
                    val nombre     = doc.getString("nombre") ?: ""
                    val grupo      = doc.getString("grupo") ?: "Otros"
                    val cantidad   = (doc.getLong("cantidad") ?: 0).toInt()
                    val comentario = doc.getString("comentario") ?: ""
                    val opciones   = doc.get("opciones") as? Map<String, String> ?: emptyMap()
                    val precio     = doc.getLong("precio") ?: 0L
                    val enviado    = doc.getBoolean("enviado") ?: true
                    val anulado    = doc.getBoolean("anulado") ?: false

                    val tipo = if (cantidad >= 0) "pos" else "neg"

                    val key = "$nombre|$comentario|$opciones|$enviado|$tipo|$anulado"

                    if (mapa.containsKey(key)) {

                        val existente = mapa[key]!!
                        existente.cantidad += cantidad

                    } else {

                        mapa[key] = ItemPedido(
                            id         = originalId ?: id,
                            productoId = productoId,
                            nombre     = nombre,
                            grupo      = grupo,
                            cantidad   = cantidad,
                            comentario = comentario,
                            opciones   = opciones,
                            precio     = precio,
                            enviado    = enviado,
                            anulado    = anulado
                        )
                    }
                }

                // sorted by canonical group order, then alphabetically within each group
                val listaOrdenada = mapa.values.sortedWith(
                    compareBy(
                        { ORDEN_GRUPOS[it.grupo] ?: 99 },
                        { it.nombre }
                    )
                )

                items.addAll(listaOrdenada)

                lista.adapter = PedidoAdapter()
            }
    }

    inner class PedidoAdapter : BaseAdapter() {

        override fun getCount(): Int = items.size

        override fun getItem(position: Int): Any = items[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(
            position: Int,
            convertView: View?,
            parent: ViewGroup?
        ): View {

            val view = convertView ?: LayoutInflater.from(this@PedidoTotalActivity)
                .inflate(R.layout.item_pedido, parent, false)

            val item = items[position]

            val txtHeaderCategoria = view.findViewById<TextView>(R.id.txtHeaderCategoria)
            val txtNombre          = view.findViewById<TextView>(R.id.txtNombre)
            val txtComentario      = view.findViewById<TextView>(R.id.txtComentario)
            val txtCantidad        = view.findViewById<TextView>(R.id.txtCantidad)
            val btnMas             = view.findViewById<Button>(R.id.btnMas)
            val btnMenos           = view.findViewById<Button>(R.id.btnMenos)

            val grupoActual = item.grupo.uppercase()

            if (position == 0 || items[position - 1].grupo.uppercase() != grupoActual) {
                txtHeaderCategoria.visibility = View.VISIBLE
                txtHeaderCategoria.text = grupoActual
            } else {
                txtHeaderCategoria.visibility = View.GONE
            }

            var nombreFinal = item.nombre

            if (!item.enviado) {
                nombreFinal += " (Act)"
            }

            if (item.anulado) {

                txtNombre.text  = "~~$nombreFinal~~"
                txtNombre.alpha = 0.5f

            } else {

                txtNombre.text  = nombreFinal
                txtNombre.alpha = 1f
            }

            if (item.comentario.isNotEmpty()) {

                txtComentario.text       = item.comentario
                txtComentario.visibility = View.VISIBLE

            } else {

                txtComentario.visibility = View.GONE
            }

            txtCantidad.text = item.cantidad.toString()

            val bloqueado = estadoActual == "cuenta"

            btnMas.isEnabled   = !bloqueado
            btnMenos.isEnabled = !bloqueado

            if (item.cantidad < 0) {
                btnMenos.isEnabled = false
            }

            btnMas.setOnClickListener {

                if (estadoActual == "cuenta") {

                    Toast.makeText(this@PedidoTotalActivity, "Cuenta en proceso", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                if (estadoActual == "enviado") {

                    iniciarEdicionTemporal {

                        db.collection(obtenerColeccionActual())
                            .whereEqualTo("pedido_id", pedidoId)
                            .whereEqualTo("original_id", item.id)
                            .get()
                            .addOnSuccessListener { result ->

                                val doc = result.documents.firstOrNull()

                                if (doc != null) {

                                    val docRef        = doc.reference
                                    val cantidadActual = doc.getLong("cantidad") ?: 0
                                    val nuevaCantidad  = cantidadActual + 1

                                    docRef.update(
                                        mapOf(
                                            "cantidad" to nuevaCantidad,
                                            "enviado"  to false
                                        )
                                    )

                                } else {

                                    val nuevoItem = hashMapOf(
                                        "original_id"  to item.id,
                                        "pedido_id"    to pedidoId,
                                        "producto_id"  to item.productoId,
                                        "nombre"       to item.nombre,
                                        "grupo"        to item.grupo,
                                        "cantidad"     to 1,
                                        "comentario"   to item.comentario,
                                        "opciones"     to item.opciones,
                                        "precio"       to item.precio,
                                        "enviado"      to false,
                                        "anulado"      to false
                                    )

                                    db.collection(obtenerColeccionActual()).add(nuevoItem)
                                }

                                cargarPedido()
                                verificarAnulacion(item)

                                Toast.makeText(
                                    this@PedidoTotalActivity,
                                    "Editando pedido...",
                                    Toast.LENGTH_SHORT
                                ).show()

                                verificarCambiosPendientes()
                                actualizarBotonesSegunEstado()
                            }
                    }

                    return@setOnClickListener
                }

                // otherwise it's the plain activo state, no temp collection involved yet
                item.cantidad++

                txtCantidad.text = item.cantidad.toString()

                db.collection("detalle_pedido")
                    .document(item.id)
                    .update("cantidad", FieldValue.increment(1))
            }

            btnMenos.setOnClickListener {

                if (estadoActual == "cuenta") {

                    Toast.makeText(this@PedidoTotalActivity, "Cuenta en proceso", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                if (estadoActual == "enviado") {

                    iniciarEdicionTemporal {

                        val queryTodos = db.collection(obtenerColeccionActual())
                            .whereEqualTo("pedido_id", pedidoId)
                            .whereEqualTo("producto_id", item.productoId)

                        queryTodos.get().addOnSuccessListener { result ->

                            val docsFiltrados = result.filter { doc ->

                                val nombreDoc     = doc.getString("nombre") ?: ""
                                val comentarioDoc = doc.getString("comentario") ?: ""
                                val opcionesDoc   = doc.get("opciones") as? Map<String, String> ?: emptyMap()

                                nombreDoc     == item.nombre &&
                                        comentarioDoc == item.comentario &&
                                        opcionesDoc   == item.opciones
                            }

                            var totalPositivos = 0L
                            var totalNegativos = 0L

                            var docNegativo: DocumentSnapshot? = null

                            for (doc in docsFiltrados) {

                                val cant = doc.getLong("cantidad") ?: 0

                                if (cant > 0) {
                                    totalPositivos += cant
                                }

                                if (cant < 0) {

                                    totalNegativos += cant

                                    if (doc.getBoolean("enviado") == false) {
                                        docNegativo = doc
                                    }
                                }
                            }

                            val totalNegAbs = kotlin.math.abs(totalNegativos)

                            if (totalNegAbs >= totalPositivos) {

                                Toast.makeText(
                                    this@PedidoTotalActivity,
                                    "No puedes restar más de lo pedido",
                                    Toast.LENGTH_SHORT
                                ).show()

                                return@addOnSuccessListener
                            }

                            if (docNegativo != null) {

                                val cantidadActual = docNegativo!!.getLong("cantidad") ?: 0
                                val nuevaCantidad  = cantidadActual - 1

                                db.collection(obtenerColeccionActual())
                                    .document(docNegativo!!.id)
                                    .update(
                                        mapOf(
                                            "cantidad" to nuevaCantidad,
                                            "enviado"  to false
                                        )
                                    )

                            } else {

                                val nuevoItem = hashMapOf(
                                    "pedido_id"    to pedidoId,
                                    "producto_id"  to item.productoId,
                                    "nombre"       to item.nombre,
                                    "grupo"        to item.grupo,
                                    "cantidad"     to -1,
                                    "comentario"   to item.comentario,
                                    "opciones"     to item.opciones,
                                    "precio"       to item.precio,
                                    "enviado"      to false,
                                    "anulado"      to false
                                )
                                db.collection(obtenerColeccionActual()).add(nuevoItem)
                            }
                            cargarPedido()
                            verificarAnulacion(item)
                            Toast.makeText(
                                this@PedidoTotalActivity,
                                "Editando pedido...",
                                Toast.LENGTH_SHORT
                            ).show()
                            verificarCambiosPendientes()
                            actualizarBotonesSegunEstado()
                        }
                    }
                    return@setOnClickListener
                }
                // otherwise it's the plain activo state, no temp collection involved yet
                if (item.cantidad > 1) {
                    item.cantidad--
                    txtCantidad.text = item.cantidad.toString()
                } else {
                    items.removeAt(position)
                    notifyDataSetChanged()
                }

                db.collection("detalle_pedido")
                    .whereEqualTo("pedido_id", pedidoId)
                    .whereEqualTo("producto_id", item.productoId)
                    .get()
                    .addOnSuccessListener { result ->

                        val docsDelPlato = result.filter { doc ->

                            val nombreDoc     = doc.getString("nombre") ?: ""
                            val comentarioDoc = doc.getString("comentario") ?: ""
                            val opcionesDoc   = doc.get("opciones") as? Map<String, String> ?: emptyMap()

                            nombreDoc     == item.nombre &&
                                    comentarioDoc == item.comentario &&
                                    opcionesDoc   == item.opciones
                        }

                        val conMasDeUno = docsDelPlato.firstOrNull { (it.getLong("cantidad") ?: 0) > 1 }

                        if (conMasDeUno != null) {
                            conMasDeUno.reference.update("cantidad", FieldValue.increment(-1))
                        } else {
                            docsDelPlato.firstOrNull()?.reference?.delete()
                        }
                    }
            }

            view.setOnClickListener {

                if (estadoActual == "cuenta") {

                    Toast.makeText(this@PedidoTotalActivity, "Cuenta en proceso", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                abrirEdicionItem(item)
            }

            return view
        }
    }

    private fun abrirEdicionItem(item: ItemPedido) {

        val esEspecial =
            item.nombre.contains("Dúo") ||
                    item.nombre.contains("Trío") ||
                    item.nombre.contains("Super")

        if (esEspecial) {
            abrirDialogoOpcionesYComentario(item)
        } else {
            abrirDialogoComentario(item)
        }
    }

    private fun abrirDialogoComentario(item: ItemPedido) {

        val input = EditText(this)

        input.hint = "Agregar comentario"

        input.setText(item.comentario)

        AlertDialog.Builder(this)
            .setTitle(item.nombre)
            .setView(input)
            .setPositiveButton("Guardar") { _, _ ->

                val comentario = input.text.toString()

                actualizarItem(item.id, comentario, null)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun verificarAnulacion(item: ItemPedido) {

        db.collection(obtenerColeccionActual())
            .whereEqualTo("pedido_id", pedidoId)
            .get()
            .addOnSuccessListener { docs ->

                val docsFiltrados = docs.filter { doc ->

                    val nombreDoc     = doc.getString("nombre") ?: ""
                    val comentarioDoc = doc.getString("comentario") ?: ""
                    val opcionesDoc   = doc.get("opciones") as? Map<String, String> ?: emptyMap()

                    nombreDoc     == item.nombre &&
                            comentarioDoc == item.comentario &&
                            opcionesDoc   == item.opciones
                }

                var totalPos = 0L
                var totalNeg = 0L

                for (doc in docsFiltrados) {

                    val cant = doc.getLong("cantidad") ?: 0

                    if (cant > 0) totalPos += cant
                    if (cant < 0) totalNeg += cant
                }

                val cancelado = totalPos == kotlin.math.abs(totalNeg)

                val batch = db.batch()

                for (doc in docsFiltrados) {
                    batch.update(doc.reference, "anulado", cancelado)
                }

                batch.commit()
            }
    }

    private fun abrirDialogoOpcionesYComentario(item: ItemPedido) {

        db.collection("productos")
            .document(item.productoId)
            .get()
            .addOnSuccessListener { doc ->

                val opciones = doc.get("opciones") as? List<Map<String, Any>>
                    ?: return@addOnSuccessListener

                val layout = LinearLayout(this)

                layout.orientation = LinearLayout.VERTICAL
                layout.setPadding(32, 32, 32, 32)

                val respuestas  = mutableMapOf<String, String>()
                val radioGroups = mutableListOf<Pair<String, RadioGroup>>()

                for (opcion in opciones) {

                    val nombre  = opcion["nombre"] as? String ?: continue
                    val valores = opcion["valores"] as? List<*> ?: continue

                    val titulo = TextView(this)

                    titulo.text = nombre

                    layout.addView(titulo)

                    val radioGroup = RadioGroup(this)

                    for (valor in valores) {

                        val radio = RadioButton(this)

                        radio.text = valor.toString()

                        radioGroup.addView(radio)

                        val valorGuardado = item.opciones[nombre]

                        if (valorGuardado == valor.toString()) {

                            radio.id = View.generateViewId()
                            radioGroup.check(radio.id)
                        }
                    }

                    layout.addView(radioGroup)

                    radioGroups.add(Pair(nombre, radioGroup))
                }

                val input = EditText(this)

                input.hint = "Comentario"
                input.setText(item.comentario)

                layout.addView(input)

                AlertDialog.Builder(this)
                    .setTitle(item.nombre)
                    .setView(layout)
                    .setPositiveButton("Guardar") { _, _ ->

                        for ((nombre, group) in radioGroups) {

                            val selectedId = group.checkedRadioButtonId

                            if (selectedId == -1) {

                                Toast.makeText(this, "Falta: $nombre", Toast.LENGTH_SHORT).show()
                                return@setPositiveButton
                            }

                            val seleccionado = group.findViewById<RadioButton>(selectedId)

                            respuestas[nombre] = seleccionado.text.toString()
                        }

                        val comentario = input.text.toString()

                        actualizarItem(item.id, comentario, respuestas)
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
    }

    private fun actualizarItem(
        itemId: String,
        comentario: String,
        opciones: Map<String, String>?
    ) {

        val itemOriginal = items.find { it.id == itemId } ?: return

        val ejecutarActualizacion = { docIdReal: String, coleccion: String ->

            val updates = mutableMapOf<String, Any>(
                "comentario" to comentario,
                "enviado"    to false
            )

            if (opciones != null) {

                val nombreBase = itemOriginal.nombre
                    .lines()
                    .first()

                val nuevoNombre = buildString {

                    append(nombreBase)

                    if (nombreBase.contains("28")) {

                        append("\n- ")
                        append(opciones["Elección 1"] ?: "")
                        append("\n- ")
                        append(opciones["Elección 2"] ?: "")

                    } else {

                        append("\n- Ceviche ")
                        append(opciones["Picante"] ?: "")

                        if (opciones.containsKey("Tipo de Chicharrón")) {

                            append("\n- Chicharrón de ")
                            append(opciones["Tipo de Chicharrón"])
                        }

                        if (opciones.containsKey("Acompañamiento")) {

                            append("\n- ")
                            append(opciones["Acompañamiento"])
                        }
                    }
                }

                updates["nombre"]  = nuevoNombre
                updates["opciones"] = opciones
            }

            db.collection(coleccion)
                .document(docIdReal)
                .update(updates)
                .addOnSuccessListener {
                    if (estadoActual == "enviado") {
                        db.collection("pedidos")
                            .document(pedidoId!!)
                            .update("editando", true)
                    }

                    Toast.makeText(this, "Actualizado", Toast.LENGTH_SHORT).show()

                    cargarPedido()
                    verificarCambiosPendientes()
                    actualizarBotonesSegunEstado()
                }
        }

        if (estadoActual == "enviado") {

            iniciarEdicionTemporal {

                db.collection("detalle_pedido_temp")
                    .whereEqualTo("pedido_id", pedidoId)
                    .get()
                    .addOnSuccessListener { result ->

                        val docTemporal = result.documents.find { doc ->

                            val nombreDoc     = doc.getString("nombre") ?: ""
                            val comentarioDoc = doc.getString("comentario") ?: ""
                            val opcionesDoc   = doc.get("opciones") as? Map<String, String> ?: emptyMap()

                            nombreDoc     == itemOriginal.nombre &&
                                    comentarioDoc == itemOriginal.comentario &&
                                    opcionesDoc   == itemOriginal.opciones
                        }

                        if (docTemporal != null) {

                            ejecutarActualizacion(
                                docTemporal.id,
                                "detalle_pedido_temp"
                            )
                        }
                    }
            }

        } else {

            ejecutarActualizacion(
                itemId,
                "detalle_pedido"
            )
        }
    }

    override fun onResume() {

        super.onResume()

        db.collection("pedidos")
            .document(pedidoId!!)
            .get()
            .addOnSuccessListener { doc ->
                estadoActual   = doc.getString("estado") ?: "activo"
                usandoTemporal = doc.getBoolean("editando") ?: false
                val mesaDb = doc.getLong("mesa")?.toInt() ?: 0
                if (mesaDb != 0) {
                    numeroMesa = mesaDb
                }
                actualizarBotonesSegunEstado()
                cargarPedido()
                verificarCambiosPendientes()
            }

        yaAbrioCuenta = false
    }

    data class ItemPedido(
        val id: String,
        val productoId: String,
        val nombre: String,
        val grupo: String,
        var cantidad: Int,
        val comentario: String = "",
        val opciones: Map<String, String> = emptyMap(),
        val precio: Long = 0L,
        val enviado: Boolean = true,
        val anulado: Boolean = false
    )

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}