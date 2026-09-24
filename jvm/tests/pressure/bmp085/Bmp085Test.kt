///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-kotlin:1.2.1

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.pressure.Bmp085Full

fun main() {
    val bus = System.getenv().getOrDefault("I2C_BUS", "1").toInt()
    val addr = 0x77

    var passed = 0
    var failed = 0

    fun checkTrue(label: String, condition: Boolean) {
        if (condition) { println("PASS $label"); passed++ }
        else { println("FAIL $label"); failed++ }
    }

    I2CConnection(bus, addr).use { connection ->
        val sensor = Bmp085Full(connection)

        val t = sensor.temperature()
        checkTrue("temperature() in range [-20, 85] °C", t >= -20.0 && t <= 85.0)

        val p = sensor.pressure()
        checkTrue("pressure() in range [30000, 110000] Pa", p >= 30000.0 && p <= 110000.0)

        val alt = sensor.altitude()
        checkTrue("altitude() returns double", alt.isFinite())

        val slp = sensor.seaLevelPressure(0.0)
        checkTrue("seaLevelPressure(0.0) > 0", slp > 0.0)

        val id = sensor.chipId()
        checkTrue("chipId() == 0x55", id == 0x55)

        sensor.setOversampling(3)
        checkTrue("setOversampling(3) accepted", true)

        val oss = sensor.oversampling()
        checkTrue("oversampling() == 3", oss == 3)

        sensor.reset()
        checkTrue("reset() accepted", true)

        val tAfter = sensor.temperature()
        checkTrue("temperature() after reset in range [-20, 85] °C",
            tAfter >= -20.0 && tAfter <= 85.0)
    }

    println("===DONE: $passed passed, $failed failed===")
    exitProcess(failed == 0 ? 0 : 1)
}