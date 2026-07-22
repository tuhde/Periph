///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-kotlin:1.1.0

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.rfid.Mfrc522Minimal

fun main() {
    I2CTransport(1, 0x28).use { transport ->                  // open I²C bus 1, device 0x28, (bus, address) → I2CTransport
        val mfrc = Mfrc522Minimal(transport, Mfrc522Minimal.BUS_I2C)  // construct driver, (transport, busType=BUS_I2C) → Mfrc522Minimal

        while (true) {
            val present = mfrc.isCardPresent()                 // detect card in field, () → Boolean
            val uid = mfrc.readUid()                           // read card UID (REQA → anticollision → HLTA), () → ByteArray?
            val sb = StringBuilder("present=").append(present).append(" uid=")
            uid?.forEach { sb.append("%02X".format(it.toInt() and 0xFF)) }
            println(sb)
            Thread.sleep(500)
        }
    }
}
