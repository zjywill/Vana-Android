package com.pinapia.vana.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 粗定位 + 反地理编码成城市名。坐标永不进 prompt。
 */
class LocationProvider(private val context: Context) {
    @Volatile
    var snapshot: LocationSnapshot = LocationSnapshot.unknown
        private set

    private var lastRefreshAtMs: Long = 0L
    private var lastLat: Double? = null
    private var lastLng: Double? = null

    val isAuthorized: Boolean
        get() = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

    fun permission(): String = Manifest.permission.ACCESS_COARSE_LOCATION

    fun clear() {
        snapshot = LocationSnapshot.unknown
        lastLat = null
        lastLng = null
    }

    @SuppressLint("MissingPermission")
    suspend fun refresh(force: Boolean = false): LocationSnapshot = withContext(Dispatchers.IO) {
        if (!isAuthorized) {
            clear()
            return@withContext snapshot
        }
        val now = System.currentTimeMillis()
        if (!force && now - lastRefreshAtMs < REFRESH_MS && snapshot.isKnown) {
            return@withContext snapshot
        }
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val location = manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            ?: manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        if (location == null) {
            return@withContext snapshot
        }
        val sameCity = lastLat?.let { lat ->
            lastLng?.let { lng ->
                distanceMeters(lat, lng, location.latitude, location.longitude) < SAME_CITY_METERS
            }
        } == true
        if (!force && sameCity && snapshot.isKnown) {
            lastRefreshAtMs = now
            return@withContext snapshot
        }
        val place = reverseGeocode(location.latitude, location.longitude)
        if (place != null) {
            snapshot = LocationSnapshot(place = place)
            lastLat = location.latitude
            lastLng = location.longitude
            lastRefreshAtMs = now
        }
        snapshot
    }

    private fun reverseGeocode(lat: Double, lng: Double): String? {
        if (!Geocoder.isPresent()) return null
        return runCatching {
            val geocoder = Geocoder(context, Locale.SIMPLIFIED_CHINESE)
            @Suppress("DEPRECATION")
            val results = geocoder.getFromLocation(lat, lng, 1).orEmpty()
            val address = results.firstOrNull() ?: return null
            val city = address.locality ?: address.subAdminArea
            val region = address.adminArea
            val country = address.countryName
            val withContext = listOfNotNull(city, region, country)
                .distinct()
                .joinToString("，")
                .ifBlank { null }
            LocationSnapshot.describe(
                cityWithContext = withContext,
                cityName = city,
                regionName = country ?: region,
            )
        }.getOrNull()
    }

    private fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float {
        val result = FloatArray(1)
        android.location.Location.distanceBetween(lat1, lng1, lat2, lng2, result)
        return result[0]
    }

    companion object {
        private val REFRESH_MS = TimeUnit.MINUTES.toMillis(10)
        private const val SAME_CITY_METERS = 5_000f
    }
}
