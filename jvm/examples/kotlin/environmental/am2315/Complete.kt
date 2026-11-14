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

        val v = sensor.read()                                                    // Read temperature and humidity, () → DoubleArray {°C, %RH}
        // previous measurement; reads are kept >= 2 s apart, CRC-16 checked
        println("%.1f C, %.1f %%RH".format(v[0], v[1]))
        val t = sensor.readTemperature()                                         // Read temperature only, () → Double °C
        val rh = sensor.readHumidity()                                           // Read humidity only, () → Double %RH
        println("%.1f C, %.1f %%RH".format(t, rh))
        val raw = sensor.readRaw()                                               // Read raw words, () → IntArray {humidity x10, temperature x10 sign + magnitude}
        println("raw RH %d, raw T 0x%04X".format(raw[0], raw[1]))
        val fresh = sensor.readFresh()                                           // Real-time value, () → DoubleArray {°C, %RH}
        // two reads >= 2 s apart; returns the second
        println("fresh %.1f C, %.1f %%RH".format(fresh[0], fresh[1]))
        val regs = sensor.readRegisters(0x08, 7)                                 // Read registers, (start, count) → ByteArray
        // function 0x03, 1-10 registers, CRC verified
        println("registers 0x08.. " + regs.contentToString())
        val info = sensor.readDeviceInfo()                                       // Read device information, () → LongArray {model, version, device id}
        println("model %d, version %d, device id 0x%08X".format(info[0], info[1], info[2]))
        sensor.writeUserRegisters(0x1234, 0xABCD)                                // Write user registers, (user1, user2) → Unit
        val user = sensor.readUserRegisters()                                    // Read user registers, () → IntArray {user1, user2}
        println("user registers 0x%04X 0x%04X".format(user[0], user[1]))
        sensor.writeRegisters(0x10, byteArrayOf(0x00, 0x01))                     // Write registers, (start, data) → Unit
        // only the user registers 0x10-0x13 are allowed
        sensor.wake()                                                            // Wake the sensor, () → Unit
        // dummy write, the NACK is ignored
    }
}
