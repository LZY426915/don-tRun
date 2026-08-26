package com.youshu.app.data.agent

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import com.youshu.app.data.network.BackendApiClient
import kotlin.coroutines.resume
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@Singleton
class WeatherAgentTool @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val backendApiClient: BackendApiClient
) {
    suspend fun getWeatherContext(
        city: String,
        intent: String
    ): String = withContext(Dispatchers.IO) {
        val normalizedCity = city.trim()
        val location = if (normalizedCity.isBlank()) {
            locateByDevice()
                ?: locateByIp()
                ?: return@withContext "暂时没有自动识别到你所在的城市。你可以直接说城市名，比如：今天重庆穿什么合适？"
        } else {
            geocode(normalizedCity)
                ?: return@withContext "没有查到“$normalizedCity”的天气位置。请用户换成更明确的城市名，例如“重庆”“杭州”“广州”。"
        }
        val weather = fetchWeather(location.adcode)
        WeatherProxyParsing.formatWeatherContext(location, weather, intent)
    }

    private suspend fun locateByDevice(): WeatherLocation? {
        if (!hasLocationPermission()) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val fresh = requestFreshLocation(manager)
        val lastKnown = getBestLastKnownLocation(manager)
        val location = fresh ?: lastKnown ?: return null
        return reverseGeocode(location)
    }

    private fun hasLocationPermission(): Boolean {
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        return coarse == PackageManager.PERMISSION_GRANTED || fine == PackageManager.PERMISSION_GRANTED
    }

    private suspend fun requestFreshLocation(manager: LocationManager): Location? {
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
        for (provider in providers) {
            val location = requestSingleLocation(manager, provider)
            if (location != null) return location
        }
        return null
    }

    private suspend fun requestSingleLocation(
        manager: LocationManager,
        provider: String
    ): Location? {
        val enabled = runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)
        if (!enabled) return null

        return withTimeoutOrNull(4500) {
            suspendCancellableCoroutine { continuation ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        if (continuation.isActive) {
                            continuation.resume(location)
                        }
                        runCatching { manager.removeUpdates(this) }
                    }

                    @Deprecated("Deprecated in Android")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                    override fun onProviderEnabled(provider: String) = Unit
                    override fun onProviderDisabled(provider: String) {
                        if (continuation.isActive) {
                            continuation.resume(null)
                        }
                        runCatching { manager.removeUpdates(this) }
                    }
                }

                runCatching {
                    manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                }.onFailure {
                    if (continuation.isActive) {
                        continuation.resume(null)
                    }
                }
                continuation.invokeOnCancellation {
                    runCatching { manager.removeUpdates(listener) }
                }
            }
        }
    }

    private fun getBestLastKnownLocation(manager: LocationManager): Location? {
        return listOf(
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        ).mapNotNull { provider ->
            runCatching {
                if (manager.isProviderEnabled(provider)) manager.getLastKnownLocation(provider) else null
            }.getOrNull()
        }.maxByOrNull { it.time }
    }

    private suspend fun reverseGeocode(location: Location): WeatherLocation? {
        val root = backendApiClient.postJsonObject(
            REVERSE_GEOCODE_PATH,
            buildJsonObject {
                put("longitude", location.longitude)
                put("latitude", location.latitude)
            }
        )
        return WeatherProxyParsing.parseReverseGeocode(root)
    }

    private suspend fun locateByIp(): WeatherLocation? {
        val root = backendApiClient.postJsonObject(
            IP_LOCATION_PATH,
            buildJsonObject { }
        )
        return WeatherProxyParsing.parseIpLocation(root)
    }

    private suspend fun geocode(city: String): WeatherLocation? {
        val root = backendApiClient.postJsonObject(
            GEOCODE_PATH,
            buildJsonObject {
                put("city", city)
                put("address", city)
            }
        )
        return WeatherProxyParsing.parseGeocode(root, city)
    }

    private suspend fun fetchWeather(adcode: String): WeatherSnapshot {
        val liveRoot = backendApiClient.postJsonObject(
            WEATHER_PATH,
            buildJsonObject {
                put("adcode", adcode)
                put("extensions", "base")
            }
        )
        val forecastRoot = backendApiClient.postJsonObject(
            WEATHER_PATH,
            buildJsonObject {
                put("adcode", adcode)
                put("extensions", "all")
            }
        )
        return WeatherProxyParsing.parseWeather(liveRoot, forecastRoot)
    }

    private companion object {
        const val IP_LOCATION_PATH = "/v1/amap/ip-location"
        const val GEOCODE_PATH = "/v1/amap/geocode"
        const val REVERSE_GEOCODE_PATH = "/v1/amap/reverse-geocode"
        const val WEATHER_PATH = "/v1/amap/weather"
    }
}

internal object WeatherProxyParsing {
    fun parseReverseGeocode(root: JsonObject): WeatherLocation? {
        requireProviderSuccess(root, "逆地理编码")
        val regeocode = root["regeocode"]?.jsonObject ?: return null
        val addressComponent = regeocode["addressComponent"]?.jsonObject ?: return null
        val adcode = addressComponent.string("adcode")
            .takeIf { it.isNotBlank() && it != "[]" }
            ?: return null
        return WeatherLocation(
            adcode = adcode,
            formattedAddress = regeocode.string("formatted_address"),
            province = addressComponent.string("province"),
            city = addressComponent.string("city"),
            district = addressComponent.string("district"),
            source = "手机定位"
        )
    }

    fun parseIpLocation(root: JsonObject): WeatherLocation? {
        requireProviderSuccess(root, "IP定位")
        val adcode = root.string("adcode").takeIf { it.isNotBlank() && it != "[]" } ?: return null
        val province = root.string("province")
        val city = root.string("city")
        return WeatherLocation(
            adcode = adcode,
            formattedAddress = listOf(province, city)
                .filter { it.isNotBlank() && it != "[]" }
                .distinct()
                .joinToString(""),
            province = province,
            city = city,
            district = "",
            source = "当前网络定位"
        )
    }

    fun parseGeocode(root: JsonObject, requestedCity: String): WeatherLocation? {
        requireProviderSuccess(root, "地理编码")
        val first = root["geocodes"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
        val adcode = first.string("adcode").takeIf { it.isNotBlank() } ?: return null
        return WeatherLocation(
            adcode = adcode,
            formattedAddress = first.string("formatted_address").ifBlank { requestedCity },
            province = first.string("province"),
            city = first.string("city"),
            district = first.string("district"),
            source = "手动指定城市"
        )
    }

    fun parseWeather(liveRoot: JsonObject, forecastRoot: JsonObject): WeatherSnapshot {
        requireProviderSuccess(liveRoot, "实时天气")
        requireProviderSuccess(forecastRoot, "天气预报")
        val live = liveRoot["lives"]?.jsonArray?.firstOrNull()?.jsonObject
        val forecast = forecastRoot["forecasts"]?.jsonArray?.firstOrNull()?.jsonObject
        val casts = forecast?.get("casts")?.jsonArray.orEmpty().map { element ->
            val obj = element.jsonObject
            WeatherCast(
                date = obj.string("date"),
                week = obj.string("week"),
                dayWeather = obj.string("dayweather"),
                nightWeather = obj.string("nightweather"),
                dayTemp = obj.string("daytemp"),
                nightTemp = obj.string("nighttemp"),
                dayWind = obj.string("daywind"),
                nightWind = obj.string("nightwind"),
                dayPower = obj.string("daypower"),
                nightPower = obj.string("nightpower")
            )
        }
        return WeatherSnapshot(
            liveWeather = live?.string("weather").orEmpty(),
            liveTemperature = live?.string("temperature").orEmpty(),
            liveHumidity = live?.string("humidity").orEmpty(),
            liveWindDirection = live?.string("winddirection").orEmpty(),
            liveWindPower = live?.string("windpower").orEmpty(),
            reportTime = live?.string("reporttime").orEmpty(),
            casts = casts
        )
    }

    fun formatWeatherContext(
        location: WeatherLocation,
        weather: WeatherSnapshot,
        intent: String
    ): String {
        val today = weather.casts.getOrNull(0)
        val tomorrow = weather.casts.getOrNull(1)
        return buildString {
            appendLine("天气数据源：高德天气")
            appendLine("天气查询地点：${location.displayName}，adcode=${location.adcode}，来源：${location.source}")
            appendLine("用户意图：${intent.ifBlank { "天气相关建议" }}")
            appendLine(
                "当前天气：${weather.liveWeather.ifBlank { "未知" }}，" +
                    "气温${weather.liveTemperature.tempText()}，" +
                    "湿度${weather.liveHumidity.percentText()}，" +
                    "风向${weather.liveWindDirection.ifBlank { "未知" }}，" +
                    "风力${weather.liveWindPower.powerText()}，" +
                    "发布时间${weather.reportTime.ifBlank { "未知" }}"
            )
            today?.let { appendLine("今天预报：${it.formatForecastLine()}") }
            tomorrow?.let { appendLine("明天预报：${it.formatForecastLine()}") }
            appendLine("请结合上述真实天气，用自然中文给出穿衣、饮食、带伞、防晒、儿童/老人注意事项等建议。")
        }.trim()
    }

    private fun requireProviderSuccess(root: JsonObject, serviceName: String) {
        if (root.string("status") != "1") {
            error("${serviceName}暂时不可用，请稍后重试。")
        }
    }

    private fun WeatherCast.formatForecastLine(): String {
        val weather = if (dayWeather == nightWeather || nightWeather.isBlank()) {
            dayWeather.ifBlank { "未知" }
        } else {
            "$dayWeather 转 $nightWeather"
        }
        val wind = if (dayWind == nightWind || nightWind.isBlank()) dayWind else "$dayWind 转 $nightWind"
        val power = if (dayPower == nightPower || nightPower.isBlank()) dayPower else "$dayPower 转 $nightPower"
        return "$date，$weather，${nightTemp.tempText()}~${dayTemp.tempText()}，${wind.ifBlank { "未知" }} 风，风力${power.ifBlank { "未知" }}"
    }

    private fun JsonObject.string(key: String): String =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull().orEmpty()

    private fun String.tempText(): String = trim().takeIf { it.isNotBlank() }?.let { "${it}℃" } ?: "未知"
    private fun String.percentText(): String = trim().takeIf { it.isNotBlank() }?.let { "$it%" } ?: "未知"
    private fun String.powerText(): String = trim().takeIf { it.isNotBlank() } ?: "未知"
}

internal data class WeatherLocation(
    val adcode: String,
    val formattedAddress: String,
    val province: String,
    val city: String,
    val district: String,
    val source: String
) {
    val displayName: String
        get() = listOf(formattedAddress, province, city, district)
            .filter { it.isNotBlank() && it != "[]" }
            .distinct()
            .joinToString("，")
            .ifBlank { formattedAddress }
}

internal data class WeatherSnapshot(
    val liveWeather: String,
    val liveTemperature: String,
    val liveHumidity: String,
    val liveWindDirection: String,
    val liveWindPower: String,
    val reportTime: String,
    val casts: List<WeatherCast>
)

internal data class WeatherCast(
    val date: String,
    val week: String,
    val dayWeather: String,
    val nightWeather: String,
    val dayTemp: String,
    val nightTemp: String,
    val dayWind: String,
    val nightWind: String,
    val dayPower: String,
    val nightPower: String
)
