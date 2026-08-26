package com.youshu.app.data.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherProxyParsingTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun parsesLiveAndForecastResponsesIntoExistingContextFormat() {
        val location = WeatherProxyParsing.parseIpLocation(
            parse("""{"status":"1","province":"浙江省","city":"杭州市","adcode":"330100"}""")
        )!!
        val snapshot = WeatherProxyParsing.parseWeather(
            liveRoot = parse(
                """{"status":"1","lives":[{"weather":"晴","temperature":"28","humidity":"55","winddirection":"东","windpower":"3","reporttime":"2026-08-27 12:00:00"}]}"""
            ),
            forecastRoot = parse(
                """{"status":"1","forecasts":[{"casts":[{"date":"2026-08-27","week":"4","dayweather":"晴","nightweather":"多云","daytemp":"31","nighttemp":"24","daywind":"东","nightwind":"东","daypower":"1-3","nightpower":"1-3"},{"date":"2026-08-28","week":"5","dayweather":"阵雨","nightweather":"阵雨","daytemp":"29","nighttemp":"23","daywind":"南","nightwind":"南","daypower":"1-3","nightpower":"1-3"}]}]}"""
            )
        )

        val context = WeatherProxyParsing.formatWeatherContext(location, snapshot, "穿衣建议")

        assertTrue(context.contains("天气数据源：高德天气"))
        assertTrue(context.contains("杭州市"))
        assertTrue(context.contains("当前天气：晴"))
        assertTrue(context.contains("明天预报：2026-08-28"))
        assertTrue(context.contains("用户意图：穿衣建议"))
    }

    @Test
    fun parseGeocodeUsesRequestedCityWhenFormattedAddressIsBlank() {
        val location = WeatherProxyParsing.parseGeocode(
            parse("""{"status":"1","geocodes":[{"formatted_address":"","province":"江苏省","city":"南京市","district":"","adcode":"320100"}]}"""),
            requestedCity = "南京"
        )!!

        assertEquals("320100", location.adcode)
        assertTrue(location.displayName.contains("南京"))
        assertEquals("手动指定城市", location.source)
    }

    @Test
    fun providerBusinessErrorIsRejected() {
        val failure = runCatching {
            WeatherProxyParsing.parseIpLocation(
                parse("""{"status":"0","info":"INVALID_USER_KEY"}""")
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("IP定位暂时不可用，请稍后重试。", failure?.message)
    }

    private fun parse(value: String) = json.parseToJsonElement(value).jsonObject
}
