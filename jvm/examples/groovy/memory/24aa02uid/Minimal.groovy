///usr/bin/env jbang "$0" "$@" ; exit $?
//GROOVY 4
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-groovy:1.1.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.memory.Eeprom24Aa02UidMinimal

def connection = new I2CConnection(1, (byte) 0x50)                                // open I²C bus 1, device 0x50, (bus, address) → I2CConnection
try {
    def eeprom = new Eeprom24Aa02UidMinimal(connection)                           // construct driver, (connection) → Eeprom24Aa02UidMinimal

    5.times {
        def uid = eeprom.readUid()                                              // read 32-bit unique serial number, () → byte[]
        def hex = uid.collect { String.format('%02X', it & 0xFF) }.join('')
        println("UID: ${hex}")
        Thread.sleep(2000)
    }
} finally {
    connection.close()
}
