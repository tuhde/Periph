///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-kotlin:1.6.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.rfid.Pn532Minimal

fun main() {
    val bus  = System.getenv().getOrDefault("I2C_BUS", "1").toInt()
    val addr = System.getenv().getOrDefault("I2C_ADDR", "0x24").replaceFirst("^0[xX]".toRegex(), "").toInt(16)

    I2CConnection(bus, addr).use { connection ->
        val reader = Pn532Minimal(connection)                                                       // Create PN532 driver, (connection) → Pn532Minimal
        reader.init()                                                                               // Wake, check firmware, SAM normal mode, finite retries, () → Unit

        while (true) {
            val uid = reader.readUid()                                                              // Detect a type A card, (timeout=1.0 s) → ByteArray?
            println("UID " + (uid?.joinToString("") { "%02X".format(it) } ?: "none"))
            Thread.sleep(1000)
        }
    }
}
