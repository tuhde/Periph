///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT
//DEPS com.pi4j:pi4j-core:2.7.0
//DEPS com.pi4j:pi4j-plugin-raspberrypi:2.7.0
//DEPS com.pi4j:pi4j-plugin-linuxfs:2.7.0

import com.pi4j.Pi4J
import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.pressure.Bmp180Full

var passed = 0
var failed = 0

fun checkTrue(label: String, condition: Boolean) {
    if (condition) { println("PASS $label"); passed++ }
    else           { println("FAIL $label"); failed++ }
}

fun main() {
    val bus = System.getenv("I2C_BUS")?.toInt() ?: 1
    // BMP180 has a fixed I²C address of 0x77; I2C_ADDR is intentionally ignored.
    val addr = 0x77

    val pi4j = Pi4J.newAutoContext()
    try {
        I2CTransport(pi4j, bus, addr).use { transport ->

            val sensor = Bmp180Full(transport)

            // temperature() — must be in sensor operating range
            val t = sensor.temperature()
            checkTrue("temperature() in range [-20, 85] °C", t >= -20.0 && t <= 85.0)

            // pressure() — valid range per datasheet
            val p = sensor.pressure()
            checkTrue("pressure() in range [300, 1100] hPa", p >= 300.0 && p <= 1100.0)

            // altitude() — just checks it returns a double without throwing
            val alt = sensor.altitude()
            checkTrue("altitude() returns double", alt.isFinite())

            // seaLevelPressure(0.0) — must yield a positive value
            val slp = sensor.seaLevelPressure(0.0)
            checkTrue("seaLevelPressure(0.0) > 0", slp > 0.0)

            // chipId() — must return 0x55
            val id = sensor.chipId()
            checkTrue("chipId() == 0x55", id == 0x55)

            // setOversampling(3) — must be accepted without exception
            sensor.setOversampling(3)
            checkTrue("setOversampling(3) accepted", true)

            // oversampling() — must reflect the value just set
            val oss = sensor.oversampling()
            checkTrue("oversampling() == 3", oss == 3)

            // reset() — must complete without exception and keep sensor functional
            sensor.reset()
            checkTrue("reset() accepted", true)

            // verify sensor still works after reset
            val tAfter = sensor.temperature()
            checkTrue("temperature() after reset in range [-20, 85] °C",
                      tAfter >= -20.0 && tAfter <= 85.0)
        }
    } finally {
        pi4j.shutdown()
    }

    println("===DONE: $passed passed, $failed failed===")
    System.exit(if (failed == 0) 0 else 1)
}
