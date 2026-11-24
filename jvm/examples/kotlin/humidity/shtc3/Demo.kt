///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.4
//DEPS it.uhde:periph-kotlin:1.5.4

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.humidity.Shtc3Full

fun main() {
    val bus  = System.getenv().getOrDefault("I2C_BUS", "1").toInt()
    val addr = System.getenv().getOrDefault("I2C_ADDR", "0x70").replaceFirst("^0[xX]", "").toInt(16)

    I2CConnection(bus, addr).use { connection ->
        val sensor = Shtc3Full(connection)                                                                 // Create SHTC3 driver, (connection) → Shtc3Full
        sensor.init()                                                                                       // Soft reset and ID check, () → void

        // --- Identify the chip ---
        // The ID word carries the SHTC3 product code in bits 11 and 5:0.
        System.out.printf("id 0x%04X%n", sensor.readId())                                                   // Read the ID word, () → int

        // --- Battery-friendly climate logger: 6 readings, 10 s apart ---
        // The sensor sleeps (0.3 uA) between the readings; every read wakes it.
        for (i in 0 until 6) {
            val m = sensor.read()                                                                           // Read temperature and humidity, () → double[] {°C, %RH}
            System.out.printf("%.2f C  %.1f %%RH%n", m[0], m[1])
            Thread.sleep(10000L)
        }

        // --- Compare normal and low-power mode ---
        // Low power measures in 0.7 ms instead of 10.8 ms at slightly higher noise.
        val n = sensor.read()                                                                               // Read temperature and humidity, () → double[] {°C, %RH}
        System.out.printf("normal    %.2f C %.1f %%RH%n", n[0], n[1])
        sensor.setLowPower(true)                                                                            // Select low-power mode, (on) → void
        val l = sensor.read()
        System.out.printf("low power %.2f C %.1f %%RH%n", l[0], l[1])
        sensor.setLowPower(false)

        // --- Dew point and raw words, then finish asleep ---
        System.out.printf("dew point %.2f C%n", sensor.dewPoint())                                          // Compute dew point, () → double °C
        val raw = sensor.readRaw()                                                                          // Read raw codes, () → int[] {raw temperature, raw humidity}
        System.out.printf("raw T %d raw RH %d%n", raw[0], raw[1])
        sensor.sleep()                                                                                      // Sleep command, () → void
    }
}
