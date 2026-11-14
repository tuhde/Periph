///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-kotlin:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.environmental.AM2315Full

fun main() {
    val bus  = System.getenv().getOrDefault("I2C_BUS", "1").toInt()
    val addr = System.getenv().getOrDefault("I2C_ADDR", "0x5C").replaceFirst("^0[xX]", "").toInt(16)

    I2CConnection(bus, addr).use { connection ->
        val sensor = AM2315Full(connection)                                      // Create AM2315 driver, (connection) → void

        val info = sensor.readDeviceInfo()                                       // Read device information, () → LongArray {model, version, device id}
        println("model %d, version %d, device id 0x%08X".format(info[0], info[1], info[2]))

        // --- Weather-station logger: a sample every 5 s, min/max tracking ---
        // The sensor measures after a communication and returns that result on the next
        // one, so every sample is the previous measurement; the driver enforces the >= 2 s
        // spacing and the CRC-16 check.
        var tMin = 1000.0; var tMax = -1000.0; var hMin = 1000.0; var hMax = -1000.0
        for (i in 1..30) {
            val v = if (i % 10 == 0) sensor.readFresh()                                             // Real-time value, () → DoubleArray {°C, %RH}
                    else sensor.read()                                                              // Read temperature and humidity, () → DoubleArray {°C, %RH}
            val t = v[0]; val rh = v[1]
            tMin = minOf(tMin, t); tMax = maxOf(tMax, t)
            hMin = minOf(hMin, rh); hMax = maxOf(hMax, rh)
            // Magnus formula
            val g = Math.log(maxOf(rh, 0.1) / 100.0) + 17.62 * t / (243.12 + t)
            val dew = 243.12 * g / (17.62 - g)
            println("%s  %.1f C  %.1f %%RH  dew point %.1f C  (T %.1f..%.1f, RH %.1f..%.1f)".format(
                java.time.LocalTime.now().withNano(0), t, rh, dew, tMin, tMax, hMin, hMax))
            Thread.sleep(3000)
        }

        // --- User registers as a persistent counter ---
        sensor.writeUserRegisters(30, 0xBEEF)                                    // Write user registers, (user1, user2) → Unit
        val user = sensor.readUserRegisters()                                    // Read user registers, () → IntArray {user1, user2}
        println("user registers %d 0x%04X".format(user[0], user[1]))
    }
}
