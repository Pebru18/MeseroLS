package com.sebastianleguia.meserols

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.firebase.firestore.FirebaseFirestore

class PedidoActivity : AppCompatActivity() {

    data class Producto(
        val id: String,
        val nombre: String,
        val opciones: List<Map<String, Any>>? = null,
        val categoria: String? = null,
        val precio: Long? = null,
        val esMarino: Boolean = false
    )

    private lateinit var listaProductos: ListView
    private val db = FirebaseFirestore.getInstance()
    private var estadoActual = "activo"
    private var pedidoId: String? = null

    // tracks in-flight firestore writes so "ver pedido total" waits for them
    // to finish instead of navigating away mid-sync
    private var operacionesPendientes = 0
    private var navegacionPendiente = false
    private lateinit var btnPedidoTotal: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pedido)

        pedidoId = intent.getStringExtra("pedido_id")
        val numeroMesa = intent.getIntExtra("numero_mesa", 1)

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        toolbar.title = "Mesa $numeroMesa"

        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val root = findViewById<LinearLayout>(R.id.rootLayout)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        listaProductos = findViewById(R.id.listaProductos)
        val scrollMenu = findViewById<ScrollView>(R.id.scrollMenu)
        val btnMenu = findViewById<Button>(R.id.btnMenu)
        val btnCarta = findViewById<Button>(R.id.btnCarta)
        val btnBebidas = findViewById<Button>(R.id.btnBebidas)
        btnPedidoTotal = findViewById(R.id.btnPedidoTotal)

        btnPedidoTotal.setOnClickListener {
            if (operacionesPendientes > 0) {
                navegacionPendiente = true
                btnPedidoTotal.text = "Sincronizando..."
                btnPedidoTotal.isEnabled = false

                listaProductos.isEnabled = false
            } else {
                irAPedidoTotal()
            }
        }

        btnMenu.setOnClickListener {
            listaProductos.visibility = View.VISIBLE
            scrollMenu.visibility = View.GONE
            cargarMenu()
        }

        btnCarta.setOnClickListener {
            scrollMenu.visibility = View.GONE
            listaProductos.visibility = View.VISIBLE
            mostrarCarta()
        }

        btnBebidas.setOnClickListener {
            scrollMenu.visibility = View.GONE
            listaProductos.visibility = View.VISIBLE
            mostrarBebidas()
        }

        db.collection("pedidos")
            .document(pedidoId!!)
            .get()
            .addOnSuccessListener { doc ->
                estadoActual = doc.getString("estado") ?: "activo"
                actualizarUI()
            }
    }

    private fun registrarOperacion() {
        operacionesPendientes++
    }

    private fun finalizarOperacion() {
        operacionesPendientes--
        if (operacionesPendientes <= 0) {
            operacionesPendientes = 0
            if (navegacionPendiente) {
                navegacionPendiente = false
                irAPedidoTotal()
            }
        }
    }

    private fun irAPedidoTotal() {
        val numeroMesa = intent.getIntExtra("numero_mesa", 1)
        val intentTotal = Intent(this, PedidoTotalActivity::class.java)
        intentTotal.putExtra("pedido_id", pedidoId)
        intentTotal.putExtra("numero_mesa", numeroMesa)

        // reset the button in case the user comes back to this screen later
        btnPedidoTotal.text = "Ver Pedido Total"
        btnPedidoTotal.isEnabled = true

        startActivity(intentTotal)
    }

    private fun cargarMenu() {
        db.collection("subproductos")
            .whereIn("padre", listOf("menu", "menumarino"))
            .get()
            .addOnSuccessListener { result ->
                val listaNombres = mutableListOf<String>()
                val listaProductosMenu = mutableListOf<Producto>()

                val listaOrdenada = result.sortedWith(
                    compareBy(
                        { if (it.getString("padre") == "menu") 0 else 1 },
                        { if (it.getString("categoria") == "entrada") 0 else 1 }
                    )
                )

                for (doc in listaOrdenada) {
                    val nombre = doc.getString("nombre") ?: ""
                    val categoria = doc.getString("categoria") ?: ""
                    val precio = doc.getLong("precio") ?: 0L
                    val padre = doc.getString("padre") ?: ""
                    val esMarino = padre == "menumarino"

                    val texto = if (categoria == "entrada") {
                        if (esMarino) "$nombre (entrada marina)" else "$nombre (entrada)"
                    } else {
                        if (esMarino) "$nombre (segundo marino) - S/ %.2f".format(precio / 100.0)
                        else "$nombre (segundo) - S/ %.2f".format(precio / 100.0)
                    }

                    listaNombres.add(texto)
                    listaProductosMenu.add(
                        Producto(id = doc.id, nombre = nombre, categoria = categoria, precio = precio, esMarino = esMarino)
                    )
                }

                val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, listaNombres)
                listaProductosUI_Menu(listaProductosMenu, adapter)
            }
    }

    private fun listaProductosUI_Menu(productos: List<Producto>, adapter: ArrayAdapter<String>) {
        listaProductos.adapter = adapter
        listaProductos.setOnItemClickListener { _, _, position, _ ->
            val producto = productos[position]
            val categoria = producto.categoria ?: ""
            val precio = producto.precio ?: 0L

            obtenerEstadoActual {
                if (estadoActual == "cuenta") {
                    Toast.makeText(this, "Cuenta en proceso", Toast.LENGTH_SHORT).show()
                    return@obtenerEstadoActual
                }

                if (categoria == "entrada") {
                    agregarEntrada(producto.nombre, producto.esMarino)
                } else {
                    agregarSegundo(producto.nombre, precio, producto.esMarino)
                }
                Toast.makeText(this, "${producto.nombre} agregado", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun agregarEntrada(nombre: String, esMarino: Boolean) {
        val ejecutarInsercion = {
            val coleccion = if (estadoActual == "enviado") "detalle_pedido_temp" else "detalle_pedido"
            val data = hashMapOf(
                "pedido_id" to pedidoId,
                "producto_id" to if (esMarino) "menumarino" else "menu",
                "nombre" to if (esMarino) "Entrada Marina: $nombre" else "Entrada: $nombre",
                "grupo" to if (esMarino) "Entrada Marina" else "Entrada",
                "cantidad" to 1,
                "precio" to 0,
                "enviado" to false
            )

            registrarOperacion()
            db.collection(coleccion).add(data).addOnCompleteListener { finalizarOperacion() }
        }

        if (estadoActual == "enviado") iniciarEdicionTemporal { ejecutarInsercion() }
        else ejecutarInsercion()
    }

    private fun agregarSegundo(nombre: String, precio: Long, esMarino: Boolean) {
        val ejecutarInsercion = {
            val coleccion = if (estadoActual == "enviado") "detalle_pedido_temp" else "detalle_pedido"
            val data = hashMapOf(
                "pedido_id" to pedidoId,
                "producto_id" to if (esMarino) "menumarino" else "menu",
                "nombre" to if (esMarino) "Segundo Marino: $nombre" else "Segundo: $nombre",
                "grupo" to if (esMarino) "Segundo Marino" else "Segundo",
                "cantidad" to 1,
                "precio" to precio,
                "enviado" to false
            )

            registrarOperacion()
            db.collection(coleccion).add(data).addOnCompleteListener { finalizarOperacion() }
        }

        if (estadoActual == "enviado") iniciarEdicionTemporal { ejecutarInsercion() }
        else ejecutarInsercion()
    }

    private fun obtenerEstadoActual(callback: () -> Unit) {
        db.collection("pedidos")
            .document(pedidoId!!)
            .get()
            .addOnSuccessListener { doc ->
                estadoActual = doc.getString("estado") ?: "activo"
                callback()
            }
    }

    private fun iniciarEdicionTemporal(callback: () -> Unit) {
        registrarOperacion() // blocks navigation while the temp collection gets cloned

        db.collection("pedidos")
            .document(pedidoId!!)
            .get()
            .addOnSuccessListener { pedidoDoc ->
                val editando = pedidoDoc.getBoolean("editando") ?: false
                if (editando) {
                    callback()
                    finalizarOperacion()
                    return@addOnSuccessListener
                }

                db.collection("detalle_pedido")
                    .whereEqualTo("pedido_id", pedidoId)
                    .get()
                    .addOnSuccessListener { result ->
                        val batch = db.batch()
                        for (doc in result) {
                            val nuevoDoc = hashMapOf(
                                "pedido_id" to pedidoId,
                                "producto_id" to (doc.getString("producto_id") ?: ""),
                                "nombre" to (doc.getString("nombre") ?: ""),
                                "grupo" to (doc.getString("grupo") ?: "Otros"),
                                "cantidad" to (doc.getLong("cantidad") ?: 0),
                                "comentario" to (doc.getString("comentario") ?: ""),
                                "opciones" to (doc.get("opciones") ?: emptyMap<String, String>()),
                                "precio" to (doc.getLong("precio") ?: 0),
                                "enviado" to (doc.getBoolean("enviado") ?: true),
                                "anulado" to (doc.getBoolean("anulado") ?: false)
                            )
                            val ref = db.collection("detalle_pedido_temp").document()
                            batch.set(ref, nuevoDoc)
                        }

                        batch.commit().addOnSuccessListener {
                            db.collection("pedidos").document(pedidoId!!).update("editando", true)
                            callback()
                        }.addOnCompleteListener { finalizarOperacion() }
                    }.addOnFailureListener { finalizarOperacion() }
            }.addOnFailureListener { finalizarOperacion() }
    }

    private fun mostrarCarta() {
        cargarProductosFirestore("carta")
    }

    private fun mostrarBebidas() {
        cargarProductosFirestore("bebidas")
    }

    private fun cargarProductosFirestore(tipo: String) {
        db.collection("productos")
            .whereEqualTo("tipo", tipo)
            .get()
            .addOnSuccessListener { result ->
                val listaNombres = mutableListOf<String>()
                val listaProductos = mutableListOf<Producto>()

                for (doc in result) {
                    val nombre = doc.getString("nombre") ?: ""
                    val opciones = doc.get("opciones") as? List<Map<String, Any>>
                    val tipoProducto = doc.getString("tipo") ?: tipo
                    listaProductos.add(Producto(id = doc.id, nombre = nombre, opciones = opciones, categoria = tipoProducto))
                    listaNombres.add(nombre)
                }

                val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, listaNombres)
                listaProductosUI(listaProductos, adapter)
            }
    }

    private fun listaProductosUI(productos: List<Producto>, adapter: ArrayAdapter<String>) {
        listaProductos.adapter = adapter
        listaProductos.setOnItemClickListener { _, _, position, _ ->
            val producto = productos[position]

            obtenerEstadoActual {
                if (estadoActual == "cuenta") {
                    Toast.makeText(this, "Cuenta en proceso", Toast.LENGTH_SHORT).show()
                    return@obtenerEstadoActual
                }

                if (!producto.opciones.isNullOrEmpty()) {
                    mostrarDialogoOpciones(producto)
                    return@obtenerEstadoActual
                }

                val coleccion = if (estadoActual == "enviado") "detalle_pedido_temp" else "detalle_pedido"
                val accionAgregar = {
                    val query = db.collection(coleccion)
                        .whereEqualTo("pedido_id", pedidoId)
                        .whereEqualTo("producto_id", producto.id)
                        .whereEqualTo("comentario", "")
                        .whereEqualTo("enviado", false)

                    registrarOperacion()
                    query.get().addOnSuccessListener { documents ->
                        if (!documents.isEmpty) {
                            val doc = documents.documents[0]
                            val cantidadActual = doc.getLong("cantidad") ?: 0
                            db.collection(coleccion).document(doc.id).update("cantidad", cantidadActual + 1)
                                .addOnCompleteListener { finalizarOperacion() }
                        } else {
                            val item = hashMapOf(
                                "pedido_id" to pedidoId, "producto_id" to producto.id,
                                "nombre" to producto.nombre, "categoria" to "",
                                "grupo" to producto.categoria,
                                "cantidad" to 1, "comentario" to "",
                                "opciones" to mapOf<String, Any>(), "precio" to 0, "enviado" to false
                            )
                            db.collection(coleccion).add(item).addOnCompleteListener { finalizarOperacion() }
                        }
                        Toast.makeText(this, "${producto.nombre} agregado", Toast.LENGTH_SHORT).show()
                    }.addOnFailureListener { finalizarOperacion() }
                }

                if (estadoActual == "enviado") iniciarEdicionTemporal { accionAgregar() }
                else accionAgregar()
            }
        }
    }

    private fun mostrarDialogoOpciones(producto: Producto) {
        val opciones = producto.opciones as? List<Map<String, Any>> ?: return
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(32, 32, 32, 32)

        val respuestas = mutableMapOf<String, String>()
        val radioGroups = mutableListOf<Pair<String, RadioGroup>>()

        for (opcion in opciones) {
            val nombre = opcion["nombre"] as? String ?: continue
            val valores = opcion["valores"] as? List<*> ?: continue

            val titulo = TextView(this)
            titulo.text = nombre
            titulo.textSize = 16f
            layout.addView(titulo)

            val radioGroup = RadioGroup(this)
            for (valor in valores) {
                val radio = RadioButton(this)
                radio.text = valor.toString()
                radioGroup.addView(radio)
            }
            layout.addView(radioGroup)
            radioGroups.add(Pair(nombre, radioGroup))
        }

        AlertDialog.Builder(this)
            .setTitle(producto.nombre)
            .setView(layout)
            .setPositiveButton("Agregar") { _, _ ->
                for ((nombre, group) in radioGroups) {
                    val selectedId = group.checkedRadioButtonId
                    if (selectedId == -1) {
                        Toast.makeText(this, "Debes elegir: $nombre", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    val seleccionado = group.findViewById<RadioButton>(selectedId)
                    respuestas[nombre] = seleccionado.text.toString()
                }
                agregarProductoConOpciones(producto, respuestas)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun agregarProductoConOpciones(producto: Producto, opcionesSeleccionadas: Map<String, String>) {
        val nombreCompleto = buildString {
            append(producto.nombre)
            if (producto.nombre.contains("28")){
                append("\n- ")
                append(opcionesSeleccionadas["Elección 1"] ?: "")
                append("\n- ")
                append(opcionesSeleccionadas["Elección 2"] ?: "")
            }
            else if (producto.nombre.contains("Dúo") || producto.nombre.contains("Trío")) {
                append("\n- Ceviche ")
                append(opcionesSeleccionadas["Picante"] ?: "")
                if (opcionesSeleccionadas.containsKey("Tipo de Chicharrón")) append("\n- Chicharrón ${opcionesSeleccionadas["Tipo de Chicharrón"]}")
                if (opcionesSeleccionadas.containsKey("Acompañamiento")) append("\n- ${opcionesSeleccionadas["Acompañamiento"]}")
            }
        }

        val coleccion = if (estadoActual == "enviado") "detalle_pedido_temp" else "detalle_pedido"
        val accionAgregar = {
            val nuevoItem = hashMapOf(
                "pedido_id" to pedidoId, "producto_id" to producto.id,
                "nombre" to nombreCompleto, "cantidad" to 1,
                "grupo" to producto.categoria,
                "comentario" to "", "opciones" to opcionesSeleccionadas,
                "precio" to 0, "enviado" to false
            )

            registrarOperacion()
            db.collection(coleccion).add(nuevoItem)
                .addOnSuccessListener { Toast.makeText(this, "${producto.nombre} agregado", Toast.LENGTH_SHORT).show() }
                .addOnCompleteListener { finalizarOperacion() }
        }

        if (estadoActual == "enviado") iniciarEdicionTemporal { accionAgregar() }
        else accionAgregar()
    }

    private fun actualizarUI() {
        val btnMenu = findViewById<Button>(R.id.btnMenu)
        val btnCarta = findViewById<Button>(R.id.btnCarta)
        val btnBebidas = findViewById<Button>(R.id.btnBebidas)
        val bloqueado = estadoActual == "cuenta"

        btnMenu.isEnabled = !bloqueado
        btnCarta.isEnabled = !bloqueado
        btnBebidas.isEnabled = !bloqueado
        listaProductos.isEnabled = !bloqueado

        if (bloqueado) Toast.makeText(this, "Cuenta en proceso", Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        // in case firestore was slow and the user backed out before it resolved
        btnPedidoTotal.text = "Ver Pedido Total"
        btnPedidoTotal.isEnabled = true
        navegacionPendiente = false
        listaProductos.isEnabled = true

        obtenerEstadoActual { actualizarUI() }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
