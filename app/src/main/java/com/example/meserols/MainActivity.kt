package com.example.meserols

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.GridLayout
import android.widget.TextView
import android.view.View
import android.animation.ObjectAnimator
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.firebase.firestore.FirebaseFirestore
import android.text.InputType
import android.text.InputFilter
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

class MainActivity : AppCompatActivity() {

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val intervaloActualizacion = 30000L

    private val runnableActualizacion = object : Runnable {
        override fun run() {
            actualizarColoresMesas()
            handler.postDelayed(this, intervaloActualizacion)
        }
    }

    private val db = FirebaseFirestore.getInstance()

    // 🔥 CAMBIO IMPORTANTE: ahora es View, no Button
    private val botonesMesas = mutableMapOf<Int, View>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        val root = findViewById<LinearLayout>(R.id.rootLayout)

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        val grid = findViewById<GridLayout>(R.id.gridMesas)

        val totalMesas = 12

        for (i in 1..totalMesas) {

            val view = layoutInflater.inflate(R.layout.item_mesa, grid, false)

            val txtMesa = view.findViewById<TextView>(R.id.txtMesa)
            val txtTiempo = view.findViewById<TextView>(R.id.txtTiempo)

            txtMesa.text = "Mesa $i"
            txtTiempo.text = "0 min"

            val params = GridLayout.LayoutParams()
            params.width = 0
            params.height = 300
            params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            params.setMargins(16, 16, 16, 16)

            view.layoutParams = params

            view.setOnClickListener {

                db.collection("pedidos")
                    .whereArrayContains("mesas", i)
                    .get()
                    .addOnSuccessListener { result ->

                        var pedidoActivo: String? = null
                        var ultimoTimestamp = 0L

                        for (doc in result) {
                            val estado = doc.getString("estado")
                            val timestamp = doc.getLong("timestamp") ?: 0L

                            if (estado in listOf("activo", "enviado", "cuenta")) {
                                if (timestamp > ultimoTimestamp) {
                                    ultimoTimestamp = timestamp
                                    pedidoActivo = doc.id
                                }
                            }
                        }

                        if (pedidoActivo != null) {

                            val intent = Intent(this, PedidoActivity::class.java)
                            intent.putExtra("pedido_id", pedidoActivo)
                            intent.putExtra("numero_mesa", i)
                            startActivity(intent)

                        } else {

                            val pedido = hashMapOf(
                                "mesas" to listOf(i),
                                "estado" to "activo",
                                "timestamp" to System.currentTimeMillis(),
                                "fecha_creacion" to System.currentTimeMillis()
                            )

                            db.collection("pedidos")
                                .add(pedido)
                                .addOnSuccessListener { documentReference ->

                                    val intent = Intent(this, PedidoActivity::class.java)
                                    intent.putExtra("pedido_id", documentReference.id)
                                    intent.putExtra("numero_mesa", i)
                                    startActivity(intent)
                                }
                        }
                    }
            }

            grid.addView(view)
            botonesMesas[i] = view
        }

        actualizarColoresMesas()
    }

    private fun actualizarColoresMesas() {

        for ((numeroMesa, view) in botonesMesas) {

            val txtMesa = view.findViewById<TextView>(R.id.txtMesa)
            val txtTiempo = view.findViewById<TextView>(R.id.txtTiempo)

            db.collection("pedidos")
                .whereArrayContains("mesas", numeroMesa)
                .get()
                .addOnSuccessListener { result ->

                    var ultimoEstado = "pagado"
                    var ultimoTimestamp = 0L
                    val tiempoActual = System.currentTimeMillis()

                    for (doc in result) {
                        val estado = doc.getString("estado") ?: "pagado"
                        val timestampEnvio = doc.getLong("timestamp_envio") ?: 0L

                        if (estado !in listOf("activo", "enviado", "cuenta")) continue

                        if (estado == "enviado" || estado == "cuenta") {
                            if (timestampEnvio > ultimoTimestamp) {
                                ultimoTimestamp = timestampEnvio
                                ultimoEstado = estado
                            }
                        } else {
                            ultimoEstado = estado
                        }
                    }

                    val minutos = if (ultimoEstado in listOf("enviado", "cuenta") && ultimoTimestamp > 0) {
                        ((tiempoActual - ultimoTimestamp) / 60000).toInt()
                    } else {
                        0
                    }

                    view.alpha = 1f

                    txtMesa.text = "Mesa $numeroMesa"
                    txtTiempo.text = "${minutos} min"
                    val ripple = view.background as android.graphics.drawable.RippleDrawable
                    val shape = ripple.getDrawable(0) as android.graphics.drawable.GradientDrawable

                    when (ultimoEstado) {

                        "enviado" -> {
                            shape.setColor(getColor(R.color.mesa_ocupada))
                        }

                        "cuenta" -> {
                            shape.setColor(getColor(R.color.mesa_cuenta))

                            val anim = ObjectAnimator.ofFloat(view, "alpha", 1f, 0.5f)
                            anim.duration = 500
                            anim.repeatMode = ObjectAnimator.REVERSE
                            anim.repeatCount = ObjectAnimator.INFINITE
                            anim.start()
                        }

                        else -> {
                            shape.setColor(getColor(R.color.mesa_libre))
                        }
                    }
                }
        }
    }

    override fun onResume() {
        super.onResume()
        actualizarColoresMesas()
        handler.postDelayed(runnableActualizacion, intervaloActualizacion)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(runnableActualizacion)
    }
    override fun onCreateOptionsMenu(menu: android.view.Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        when (item.itemId) {

            R.id.menu_historial -> {
                mostrarDialogoPinHistorial()
                return true
            }

            R.id.menu_configurar -> {
                startActivity(Intent(this, ConfigurarMenuActivity::class.java))
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }
    private fun mostrarDialogoPinHistorial() {
        // Creamos el campo de texto dinámicamente
        val input = EditText(this)
        input.hint = "Ingrese PIN de 4 dígitos"
        // Lo configuramos como número y como contraseña (asteriscos/puntos)
        input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        // Limitamos a un máximo de 4 caracteres
        input.filters = arrayOf(InputFilter.LengthFilter(4))
        input.textAlignment = View.TEXT_ALIGNMENT_CENTER

        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(60, 40, 60, 10)
        layout.addView(input)

        val builder = AlertDialog.Builder(this)
            .setTitle("Acceso Restringido")
            .setMessage("Ingrese el código para ver el historial.")
            .setView(layout)
            .setPositiveButton("Entrar", null) // Lo sobreescribimos abajo para no cerrarlo de golpe
            .setNegativeButton("Cancelar", null)

        val dialog = builder.create()
        dialog.show()

        val btnEntrar = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        btnEntrar.setOnClickListener {
            val pinIngresado = input.text.toString().trim()

            if (pinIngresado.length != 4) {
                Toast.makeText(this, "El PIN debe tener 4 dígitos", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Desactivamos el botón temporalmente para que no den doble clic mientras carga Firebase
            btnEntrar.isEnabled = false

            // Buscamos en Firebase
            db.collection("passwords")
                .whereEqualTo("pass", pinIngresado)
                .get()
                .addOnSuccessListener { result ->
                    if (!result.isEmpty) {
                        // El PIN existe en la base de datos
                        abrirHistorial()
                        dialog.dismiss()
                    } else if (pinIngresado == "7492") {
                        // 🔥 PIN MAESTRO DE RESPALDO
                        abrirHistorial()
                        dialog.dismiss()
                    } else {
                        Toast.makeText(this, "PIN incorrecto", Toast.LENGTH_SHORT).show()
                        input.text.clear()
                        btnEntrar.isEnabled = true // Volvemos a activar el botón
                    }
                }
                .addOnFailureListener {
                    // Si falla el internet o Firebase, dejamos entrar con el PIN maestro
                    if (pinIngresado == "7492") {
                        abrirHistorial()
                        dialog.dismiss()
                    } else {
                        Toast.makeText(this, "Error de red o PIN incorrecto", Toast.LENGTH_SHORT).show()
                        input.text.clear()
                        btnEntrar.isEnabled = true
                    }
                }
        }
    }

    private fun abrirHistorial() {
        startActivity(Intent(this, HistorialActivity::class.java))
    }
}