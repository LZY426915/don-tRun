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
import com.youshu.app.BuildConfig
import com.youshu.app.data.local.entity.AiModelConfig
import com.youshu.app.data.repository.AiModelRepository
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

@Singleton
class WeatherAgentTool @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val aiModelRepository: AiModelRepository
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun getWeatherContext(
        city: String,
        intent: String
    ): String = withContext(Dispatchers.IO) {
        val config = aiModelRepository.getPrimaryModelForPurpose(AiModelConfig.PURPOSE_WEATHER)
        val apiKey = config?.apiKey?.trim().orEmpty()
            .ifEmpty { BuildConfig.DEFAULT_AMAP_WEB_API_KEY.trim() }
        if (apiKey.isBlank()) {
            return@withContext "天气功能需要先配置高德 Web 服务 API Key。请在「我的」→「API-Key 管理系统」中编辑高德天气的 API Key，或在 local.properties 里填写 youshu.amap.webApiKey。"
        }

        val normalizedCity = city.trim()
        val location = if (normalizedCity.isBlank()) {
            locateByDevice(apiKey)
                ?: locateByIp(apiKey)
                ?: return@withContext "暂时没有自动识别到你所在的城市。你可以直接说城市名，比如：今天重庆穿什么合适？"
        } else {
            geocode(normalizedCity, apiKey)
                ?: return@withContext "没有查到“$normalizedCity”的天气位置。请用户换成更明确的城市名，例如“重庆”“杭州”“广州”。"
        }
        val weather = fetchWeather(location.adcode, apiKey)
        formatWeatherContext(location, weather, intent)
    }

    private suspend fun locateByDevice(apiKey: String): WeatherLocation? {
        if (!hasLocationPermission()) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val fresh = requestFreshLocation(manager)
        val lastKnown = getBestLastKnownLocation(manager)
        val location = fresh ?: lastKnown ?: return null
        return reverseGeocode(location, apiKey)
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

    private fun reverseGeocode(location: Location, apiKey: String): WeatherLocation? {
        val url = "https://restapi.amap.com/v3/geocode/regeo" +
            "?location=${location.longitude},${location.latitude}&key=$apiKey&extensions=base&output=JSON"
        val root = requestAmapJson(url, "逆地理编码")
        val regeocode = root["regeocode"]?.jsonObject ?: return null
        val addressComponent = regeocode["addressComponent"]?.jsonObject ?: return null
        val adcode = addressComponent.string("adcode").takeIf { it.isNotBlank() && it != "[]" } ?: return null

        return WeatherLocation(
            adcode = adcode,
            formattedAddress = regeocode.string("formatted_address"),
            province = addressComponent.string("province"),
            city = addressComponent.string("city"),
            district = addressComponent.string("district"),
            source = "手机定位"
        )
    }

    private fun locateByIp(apiKey: String): WeatherLocation? {
        val url = "https://restapi.amap.com/v3/ip?key=$apiKey&output=JSON"
        val root = requestAmapJson(url, "IP定位")
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

    private fun geocode(city: String, apiKey: String): WeatherLocation? {
        val encoded = URLEncoder.encode(city, Charsets.UTF_8.name())
        val url = "https://restapi.amap.com/v3/geocode/geo" +
            "?address=$encoded&key=$apiKey&output=JSON"
        val root = requestAmapJson(url, "地理编码")
        val first = root["geocodes"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
        val adcode = first.string("adcode").takeIf { it.isNotBlank() } ?: return null

        return WeatherLocation(
            adcode = adcode,
            formattedAddress = first.string("formatted_address").ifBlank { city },
            province = first.string("province"),
            city = first.string("city"),
            district = first.string("district"),
            source = "手动指定城市"
        )
    }

    private fun fetchWeather(adcode: String, apiKey: String): WeatherSnapshot {
        val liveUrl = "https://restapi.amap.com/v3/weather/weatherInfo" +
            "?city=$adcode&key=$apiKey&extensions=base&output=JSON"
        val forecastUrl = "https://restapi.amap.com/v3/weather/weatherInfo" +
            "?city=$adcode&key=$apiKey&extensions=all&output=JSON"

        val liveRoot = requestAmapJson(liveUrl, "实时天气")
        val forecastRoot = requestAmapJson(forecastUrl, "天气预报")
        val live = liveRoot["lives"]?.jsonArray?.firstOrNull()?.jsonObject
        val forecast = forecastRoot["forecasts"]?.jsonArray?.firstOrNull()?.jsonObject
        val casts = forecast?.get("casts")?.jsonArray.orEmpty().mapNotNull { element ->
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

    private fun requestAmapJson(url: String, serviceName: String): JsonObject {
        val response = client.newCall(Request.Builder().url(url).get().build()).execute()
        response.use {
            if (!it.isSuccessful) {
                error("$serviceName 请求失败：HTTP ${it.code}")
            }
            val body = it.body?.string().orEmpty()
            val root = json.parseToJsonElement(body).jsonObject
            val status = root.string("status")
            if (status != "1") {
                val info = root.string("info").ifBlank { "未知错误" }
                error("$serviceName 请求失败：$info")
            }
            return root
        }
    }

    private fun formatWeatherContext(
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
            if (today != null) {
                appendLine("今天预报：${today.formatForecastLine()}")
            }
            if (tomorrow != null) {
                appendLine("明天预报：${tomorrow.formatForecastLine()}")
            }
            appendLine("请结合上述真实天气，用自然中文给出穿衣、饮食、带伞、防晒、儿童/老人注意事项等建议。")
        }.trim()
    }

    private fun WeatherCast.formatForecastLine(): String {
        val weather = if (dayWeather == nightWeather || nightWeather.isBlank()) {
            dayWeather.ifBlank { "未知" }
        } else {
            "$dayWeather 转 $nightWeather"
        }
        val wind = if (dayWind == nightWind || nightWind.isBlank()) {
            dayWind
        } else {
            "$dayWind 转 $nightWind"
        }.ifBlank { "未知" }
        val power = if (dayPower == nightPower || nightPower.isBlank()) {
            dayPower
        } else {
            "$dayPower 转 $nightPower"
        }.ifBlank { "未知" }
        return "$date，$weather，${nightTemp.tempText()}~${dayTemp.tempText()}，$wind 风，风力$power"
    }

    private fun JsonObject.string(key: String): String =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull().orEmpty()

    private fun String.tempText(): String =
        trim().takeIf { it.isNotBlank() }?.let { "${it}℃" } ?: "未知"

    private fun String.percentText(): String =
        trim().takeIf { it.isNotBlank() }?.let { "$it%" } ?: "未知"

    private fun String.powerText(): String =
        trim().takeIf { it.isNotBlank() } ?: "未知"

    private data class WeatherLocation(
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

    private data class WeatherSnapshot(
        val liveWeather: String,
        val liveTemperature: String,
        val liveHumidity: String,
        val liveWindDirection: String,
        val liveWindPower: String,
        val reportTime: String,
        val casts: List<WeatherCast>
    )

    private data class WeatherCast(
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
}
