package com.nikitayr.yrmaps

import android.Manifest
import android.animation.TypeEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PorterDuff
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.nikitayr.yrmaps.databinding.ActivityMainBinding
import org.mapsforge.core.model.LatLong
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.util.AndroidUtil
import org.mapsforge.map.layer.renderer.TileRendererLayer
import org.mapsforge.map.reader.MapFile
import org.mapsforge.map.rendertheme.InternalRenderTheme
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    companion object {
        val CRACOW = LatLong(50.0600, 19.9381)
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1
        private const val LOCATION_SETTINGS_REQUEST_CODE = 2
        private const val STORAGE_PERMISSION_REQUEST_CODE = 100
        private const val REQUEST_CODE_OPEN_DOCUMENT_TREE = 1001
    }

    private var isGpsTracking = false
    private lateinit var binding: ActivityMainBinding
    private lateinit var locationManager: LocationManager
    private var locationUpdateRunnable: Runnable? = null
    private val handler = Handler(Looper.getMainLooper())

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            if (isLocationEnabled()) {
                getLocationAndCenterMap()
            } else {
                Toast.makeText(this, "Enable location services to use your current location.", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
        } else {
            Toast.makeText(this, "Location access denied. Grant permission to use your location.", Toast.LENGTH_SHORT).show()
            centerMapOn(CRACOW)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkStoragePermission()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            val decorView = window.decorView
            val uiOptions = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            decorView.systemUiVisibility = uiOptions
        }
        supportActionBar?.hide()
        AndroidGraphicFactory.createInstance(application)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        Toast.makeText(this, "Download the map from Mapforge before opening it", Toast.LENGTH_LONG).show()

        val contract = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            result?.data?.data?.let { uri ->
                openMap(uri)
            }
        }

        binding.openMap.setOnClickListener {
            contract.launch(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
            )
        }

        binding.gpsButton.setOnClickListener { toggleGpsTracking() }

        binding.folderButton.setOnClickListener { openYrDataFolder() }

        binding.openLinkButton.setOnClickListener {
            openLink("https://download.mapsforge.org/maps/v5/")
        }
    }

    private fun toggleGpsTracking() {
        isGpsTracking = !isGpsTracking

        if (isGpsTracking) {
            startGpsTracking()
            setButtonIconColor(binding.gpsButton, Color.parseColor("#36b5ff"))
        } else {
            stopGpsTracking()
            setButtonIconColor(binding.gpsButton, Color.WHITE)
        }
    }

    private fun setButtonIconColor(button: android.widget.Button, color: Int) {
        val drawable = button.compoundDrawables[1] // Top drawable
        drawable?.mutate()?.setColorFilter(color, PorterDuff.Mode.SRC_IN)
    }

    private fun startGpsTracking() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED -> {
                if (isLocationEnabled()) {
                    locationUpdateRunnable = Runnable {
                        getLocationAndCenterMap()
                        locationUpdateRunnable?.let { handler.postDelayed(it, 5000) }
                    }
                    locationUpdateRunnable?.let { handler.post(it) }
                } else {
                    Toast.makeText(
                        this,
                        "Enable location services to use your current location.",
                        Toast.LENGTH_LONG
                    ).show()
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    isGpsTracking = false
                    setButtonIconColor(binding.gpsButton, Color.WHITE)
                }
            }
            else -> {
                locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                isGpsTracking = false
                setButtonIconColor(binding.gpsButton, Color.WHITE)
            }
        }
    }

    private fun stopGpsTracking() {
        locationUpdateRunnable?.let { handler.removeCallbacks(it) }
        locationUpdateRunnable = null
    }

    private fun checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), STORAGE_PERMISSION_REQUEST_CODE)
            }
        }
    }

    private fun openYrDataFolder() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            }
            startActivityForResult(intent, REQUEST_CODE_OPEN_DOCUMENT_TREE)
        } else {
            val dataDir = File(Environment.getExternalStorageDirectory(), "Documents/YR-data")
            if (dataDir.exists() && dataDir.isDirectory) {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.fromFile(dataDir), "*/*")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "Failed to open the folder. Please ensure a file manager is installed.", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "The 'YR-data' folder does not exist.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_OPEN_DOCUMENT_TREE && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                Toast.makeText(this, "Folder access granted: $uri", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openMap(uri: Uri) {
        val cache = AndroidUtil.createTileCache(
            this, "mycache", binding.map.model.displayModel.tileSize, 1f, binding.map.model.frameBufferModel.overdrawFactor
        )

        val stream = contentResolver.openInputStream(uri) as FileInputStream
        val mapStore = MapFile(stream)

        val renderLayer = TileRendererLayer(cache, mapStore, binding.map.model.mapViewPosition, AndroidGraphicFactory.INSTANCE)
        renderLayer.setXmlRenderTheme(InternalRenderTheme.DEFAULT)

        binding.map.layerManager.layers.add(renderLayer)
        binding.map.setBuiltInZoomControls(false)
        binding.map.mapScaleBar.isVisible = false

        binding.map.visibility = View.VISIBLE

        binding.map.postDelayed({ requestLocationAndCenter() }, 1000)
    }

    private fun requestLocationAndCenter() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED -> {
                if (isLocationEnabled()) {
                    getLocationAndCenterMap()
                } else {
                    Toast.makeText(this, "Enable location services to use your current location.", Toast.LENGTH_LONG).show()
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
            }
            else -> {
                locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
    }

    private fun isLocationEnabled(): Boolean {
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    private fun getLocationAndCenterMap() {
        try {
            val location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?:
            locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

            if (location != null) {
                logLocationToFile(location.latitude, location.longitude)
                centerMapOn(LatLong(location.latitude, location.longitude))
            } else {
                Toast.makeText(this, "Can't get location. Check settings.", Toast.LENGTH_SHORT).show()
                centerMapOn(CRACOW)
            }
        } catch (e: SecurityException) {
            Toast.makeText(this, "Location access error. Grant permission to avoid using default location.", Toast.LENGTH_SHORT).show()
            centerMapOn(CRACOW)
        }
    }

    private fun logLocationToFile(latitude: Double, longitude: Double) {
        try {
            val documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            val dataDir = File(documentsDir, "YR-data")

            if (!dataDir.exists()) {
                if (!dataDir.mkdirs()) {
                    Log.e("LocationLogging", "Failed to create directory")
                    Toast.makeText(this, "Cannot create logging directory", Toast.LENGTH_SHORT).show()
                    return
                }
            }

            val file = File(dataDir, "data.txt")

            if (!file.exists()) {
                file.createNewFile()
            }

            val currentTime = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.ENGLISH).format(Date())
            val formattedLatitude = String.format(Locale.ENGLISH, "%.2f", latitude)
            val formattedLongitude = String.format(Locale.ENGLISH, "%.2f", longitude)
            val locationData = "$formattedLatitude, $formattedLongitude | $currentTime\n"

            try {
                file.appendText(locationData)
                val absolutePath = file.absolutePath
                Toast.makeText(this, "Location logged at: $absolutePath", Toast.LENGTH_LONG).show()
                Log.d("LocationLogging", "Logged to: $absolutePath")
            } catch (e: IOException) {
                Log.e("LocationLogging", "Error writing to file", e)
                Toast.makeText(this, "Failed to log location: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("LocationLogging", "Unexpected error", e)
            Toast.makeText(this, "Unexpected logging error", Toast.LENGTH_SHORT).show()
        }
    }

    private fun centerMapOn(location: LatLong, zoomLevel: Int = 16) {
        val currentZoomLevel = binding.map.model.mapViewPosition.zoomLevel.toInt()

        val zoomAnimator = ValueAnimator.ofFloat(currentZoomLevel.toFloat(), zoomLevel.toFloat())
        zoomAnimator.duration = 800

        zoomAnimator.addUpdateListener { animation ->
            val zoom = (animation.animatedValue as Float).toInt().toByte()
            binding.map.model.mapViewPosition.zoomLevel = zoom
        }

        val centerAnimator = ValueAnimator.ofObject(LatLongEvaluator(), binding.map.model.mapViewPosition.center, location)
        centerAnimator.duration = 800

        centerAnimator.addUpdateListener { animation ->
            binding.map.model.mapViewPosition.center = animation.animatedValue as LatLong
        }

        zoomAnimator.start()
        centerAnimator.start()
    }

    private fun openLink(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Error opening link", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            STORAGE_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Storage permission granted", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Storage permission denied", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopGpsTracking()
    }

    private class LatLongEvaluator : TypeEvaluator<LatLong> {
        override fun evaluate(fraction: Float, startValue: LatLong, endValue: LatLong): LatLong {
            val lat = startValue.latitude + fraction * (endValue.latitude - startValue.latitude)
            val lon = startValue.longitude + fraction * (endValue.longitude - startValue.longitude)
            return LatLong(lat, lon)
        }
    }
}