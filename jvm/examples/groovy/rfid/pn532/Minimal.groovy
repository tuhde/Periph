///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-groovy:1.6.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.rfid.Pn532Minimal

def bus  = System.getenv().getOrDefault("I2C_BUS", "1") as int
def addr = Integer.parseInt(System.getenv().getOrDefault("I2C_ADDR", "0x24").replaceFirst("^0[xX]", ""), 16)

def connection = new I2CConnection(bus, addr)
try {
    def reader = new Pn532Minimal(connection)                                                       // Create PN532 driver, (connection) → Pn532Minimal
    reader.init()                                                                                   // Wake, check firmware, SAM normal mode, finite retries, () → void

    while (true) {
        byte[] uid = reader.readUid()                                                               // Detect a type A card, (timeout=1.0 s) → byte[] | null
        println("UID " + (uid == null ? "none" : uid.encodeHex().toString().toUpperCase()))
        Thread.sleep(1000)
    }
} finally {
    connection.close()
}
