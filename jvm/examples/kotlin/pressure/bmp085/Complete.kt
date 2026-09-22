///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.0
//DEPS it.uhde:periph-kotlin:1.2.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.pressure.Bmp085Full

fun main() {
    I2CConnection(1, 0x77).use { connection ->
        val sensor = Bmp085Full(connection)

        val id = sensor.chipId()
        println("chip ID: 0x${id.toString(16).uppercase()}")

        val oss = sensor.oversampling()
        println("oversampling: $oss")

        sensor.setOversampling(Bmp085Full.OSS_HIGH_RES)

        val t = sensor.temperature()
        println("temperature: ${t:.2f} °C")

        val p = sensor.pressure()
        println("pressure: ${p:.2f} Pa")

        val alt = sensor.altitude()
        println("altitude: ${alt:.1f} m")

        val altRef = sensor.altitude(101300.0)
        println("altitude (QNH 101300.0): ${altRef:.1f} m")

        val slp = sensor.seaLevelPressure(50.0)
        println("sea-level pressure at 50 m: ${slp:.2f} Pa")

        sensor.reset()

        sensor.setOversampling(Bmp085Full.OSS_ULP)

        println("oversampling after reset: ${sensor.oversampling()}")
    }
}