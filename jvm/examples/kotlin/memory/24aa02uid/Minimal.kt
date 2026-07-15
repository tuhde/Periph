///usr/bin/env jbang "$0" "$@" ; exit $?
//KOTLIN 2.0+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-kotlin:1.1.0

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.memory.Eeprom24Aa02UidMinimal

fun main() {
    I2CTransport(1, 0x50).use { transport ->                                    // open I²C bus 1, device 0x50, (bus, address) → I2CTransport
        val eeprom = Eeprom24Aa02UidMinimal(transport)                          // construct driver, (transport) → Eeprom24Aa02UidMinimal

        repeat(5) {
            val uid = eeprom.readUid()                                           // read 32-bit unique serial number, () → ByteArray
            println("UID: " + uid.joinToString("") { String.format("%02X", it.toInt() and 0xFF) })
            Thread.sleep(2000)
        }
    }
}
