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
        val m = sensor.read()                                                                               // Read temperature and humidity, () → double[] {°C, %RH}
                                                                                                            // normal mode, both CRCs verified, one retry on a bad CRC
        System.out.printf("%.2f C, %.1f %%RH%n", m[0], m[1])
        System.out.printf("humidity %.1f %%RH%n", sensor.readHumidity())                                    // Read humidity only, () → double %RH
        System.out.printf("temperature %.2f C%n", sensor.readTemperature())                                 // Read temperature only, () → double °C
        val raw = sensor.readRaw()                                                                          // Read raw codes, () → int[] {raw temperature, raw humidity}
                                                                                                            // 16-bit unsigned
        System.out.printf("raw T %d raw RH %d%n", raw[0], raw[1])
        System.out.printf("id 0x%04X%n", sensor.readId())                                                   // Read the ID word, () → int
                                                                                                            // (id & 0x083F) == 0x0807 for the SHTC3

        sensor.setLowPower(true)                                                                            // Select low-power mode, (on) → void
                                                                                                            // 0.7 ms measurement, slightly noisier
        val lp = sensor.read()
        System.out.printf("low power %.2f C %.1f %%RH%n", lp[0], lp[1])
        sensor.setLowPower(false)
        sensor.setClockStretching(true)                                                                     // Select clock stretching, (on) → void
                                                                                                            // needs a controller that tolerates SCL held low
        val cs = sensor.read()
        System.out.printf("stretching %.2f C %.1f %%RH%n", cs[0], cs[1])
        sensor.setClockStretching(false)
        sensor.setSleepBetween(false)                                                                       // Sleep after every read?, (on) → void
                                                                                                            // off keeps the sensor idle (higher supply current)
        val ns = sensor.read()
        System.out.printf("no sleep %.2f C %.1f %%RH%n", ns[0], ns[1])
        sensor.setSleepBetween(true)
        sensor.wake()                                                                                       // Wake-up command, () → void
        sensor.sleep()                                                                                      // Sleep command, () → void
        System.out.printf("dew point %.2f C%n", sensor.dewPoint())                                          // Compute dew point, () → double °C
                                                                                                            // one measurement, Magnus formula
        sensor.softReset()                                                                                  // Soft reset, () → void
                                                                                                            // 0x805D, leaves the sensor idle
    }
}
