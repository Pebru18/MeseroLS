package com.example.meserols

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.firebase.firestore.FirebaseFirestore

class ConfigurarMenuActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()

    private lateinit var contenedorBotones: LinearLayout
    private lateinit var contenedorLista: LinearLayout


    private var padreActual = "menu"
    private var categoriaActual = "entrada"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_configurar_menu)
        val root = findViewById<LinearLayout>(R.id.rootLayout)

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        contenedorBotones = findViewById(R.id.contenedorBotones)
        contenedorLista = findViewById(R.id.contenedorLista)

        mostrarMenus()
    }

    private fun mostrarMenus() {

        contenedorBotones.removeAllViews()

        val btnCriollo = Button(this).apply { text = "Menú Criollo" }
        val btnMarino = Button(this).apply { text = "Menú Marino" }

        contenedorBotones.addView(btnCriollo)
        contenedorBotones.addView(btnMarino)

        btnCriollo.setOnClickListener {
            padreActual = "menu"
            mostrarCategorias()
        }

        btnMarino.setOnClickListener {
            padreActual = "menumarino"
            mostrarCategorias()
        }
    }

    private fun mostrarCategorias() {

        contenedorBotones.removeAllViews()

        val btnEntrada = Button(this).apply {
            text = "Entradas"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnSegundo = Button(this).apply {
            text = "Segundos"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        contenedorBotones.addView(btnEntrada)
        contenedorBotones.addView(btnSegundo)

        btnEntrada.setOnClickListener {
            categoriaActual = "entrada"
            cargarLista()
        }

        btnSegundo.setOnClickListener {
            categoriaActual = "segundo"
            cargarLista()
        }
    }

    private fun cargarLista() {

        contenedorLista.removeAllViews()

        db.collection("subproductos")
            .whereEqualTo("padre", padreActual)
            .whereEqualTo("categoria", categoriaActual)
            .get()
            .addOnSuccessListener { result ->

                for (doc in result) {

                    val view = layoutInflater.inflate(R.layout.item_config_menu, contenedorLista, false)

                    val txtNombre = view.findViewById<TextView>(R.id.txtNombre)
                    val txtPrecio = view.findViewById<TextView>(R.id.txtPrecio)
                    val btnEliminar = view.findViewById<ImageButton>(R.id.btnEliminar)

                    val nombre = doc.getString("nombre") ?: ""
                    val precio = doc.getLong("precio") ?: 0L

                    txtNombre.text = nombre

                    if (categoriaActual == "segundo") {
                        txtPrecio.text = "S/ %.2f".format(precio / 100.0)

                        txtPrecio.setOnClickListener {
                            editarPrecio(doc.id, precio)
                        }

                    } else {
                        txtPrecio.text = ""
                    }

                    btnEliminar.setOnClickListener {
                        db.collection("subproductos").document(doc.id).delete()
                        cargarLista()
                    }

                    contenedorLista.addView(view)
                }

                // 🔥 BOTÓN AGREGAR COMO ITEM
                val btnAgregar = TextView(this)

                btnAgregar.text = "+ Agregar"
                btnAgregar.textSize = 16f
                btnAgregar.setPadding(16, 24, 16, 24)

                btnAgregar.setOnClickListener {
                    mostrarDialogAgregar()
                }

                contenedorLista.addView(btnAgregar)
            }
    }

    private fun mostrarDialogAgregar() {

        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(32, 16, 32, 16)

        val inputNombre = EditText(this)
        inputNombre.hint = "Nombre"

        layout.addView(inputNombre)

        var inputPrecio: EditText? = null

        if (categoriaActual == "segundo") {
            inputPrecio = EditText(this)
            inputPrecio.hint = "Precio (ej: 15)"
            layout.addView(inputPrecio)
        }

        AlertDialog.Builder(this)
            .setTitle("Agregar")
            .setView(layout)
            .setPositiveButton("Guardar") { _, _ ->

                val nombre = inputNombre.text.toString()
                val precio = inputPrecio?.text?.toString()?.toDoubleOrNull() ?: 0.0

                val data = hashMapOf(
                    "nombre" to nombre,
                    "categoria" to categoriaActual,
                    "padre" to padreActual,
                    "precio" to (precio * 100).toLong()
                )

                db.collection("subproductos").add(data)

                cargarLista()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun editarPrecio(docId: String, precioActual: Long) {

        val input = EditText(this)
        input.setText((precioActual / 100.0).toString())

        AlertDialog.Builder(this)
            .setTitle("Editar precio")
            .setView(input)
            .setPositiveButton("Guardar") { _, _ ->

                val nuevo = input.text.toString().toDoubleOrNull() ?: 0.0

                db.collection("subproductos")
                    .document(docId)
                    .update("precio", (nuevo * 100).toLong())

                cargarLista()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}