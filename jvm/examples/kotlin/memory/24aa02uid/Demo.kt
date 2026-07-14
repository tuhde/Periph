///usr/bin/env jbang "$0" "$@" ; exit $?
//KOTLIN 2.0+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.memory.Eeprom24Aa02UidFull

/**
 * Device-tracking demo: on startup, read the 32-bit unique serial number
 * and print it as an 8-character hex string. Maintain a 4-byte boot
 * counter in user EEPROM at 0x00-0x03. Then loop reading only the UID
 * every 2 seconds to show that the UID never changes while the counter
 * does. The two distinct areas of the chip (immutable identification
 * above 0x80, rewritable storage below 0x80) are exercised independently.
 */
fun main() {
    val samples     = 5
    val intervalMs  = 2000L

    I2CTransport(1, 0x50).use { transport ->                                    // open I²C bus 1, device 0x50, (bus, address) → I2CTransport
        val eeprom = Eeprom24Aa02UidFull(transport)                             // construct driver, (transport) → Eeprom24Aa02UidFull

        // --- Read the chip's factory-programmed 32-bit serial number ---
        // The UID at 0xFC-0xFF never changes and identifies the device
        // across the entire 256-byte address space.
        val uid = eeprom.readUid()                                               // read 32-bit unique serial number, () → ByteArray
                                                                                  // reads 4 bytes at 0xFC-0xFF
        val uidHex = uid.joinToString("") { String.format("%02X", it.toInt() and 0xFF) }
        println("Device UID: 0x$uidHex")
        val uidInt = ((uid[0].toLong() and 0xFF) shl 24) or ((uid[1].toLong() and 0xFF) shl 16)
                  or ((uid[2].toLong() and 0xFF) shl 8)  or  (uid[3].toLong() and 0xFF)
        println("Device UID int: $uidInt")

        // --- Maintain a 4-byte boot counter in user EEPROM at 0x00-0x03 ---
        // Read the existing value (or zero on a fresh chip), increment,
        // write back as 4 big-endian bytes. The user EEPROM is rewritable;
        // the UID region above 0x80 is not, so the two stay independent.
        val existing = eeprom.read(0x00, 4)                                      // sequential read, (address, length) → ByteArray
                                                                                  // reads 4 bytes from user EEPROM
        var counter = ((existing[0].toLong() and 0xFF) shl 24) or ((existing[1].toLong() and 0xFF) shl 16)
                   or ((existing[2].toLong() and 0xFF) shl 8)  or  (existing[3].toLong() and 0xFF)
        counter += 1
        val updated = byteArrayOf(
            ((counter shr 24) and 0xFF).toByte(), ((counter shr 16) and 0xFF).toByte(),
            ((counter shr 8)  and 0xFF).toByte(), (counter and 0xFF).toByte()
        )
        eeprom.write(0x00, updated)                                              // arbitrary-length write, (address, data) → Unit
                                                                                  // writes 4 bytes; waits for each chunk
        println("Boot count: $counter")

        for (n in 0 until samples) {
            // --- Loop reading the UID only, showing it never changes ---
            // The two distinct areas of the chip (immutable identification
            // above 0x80, rewritable storage below 0x80) are exercised
            // independently.
            val u = eeprom.readUid()                                             // read 32-bit unique serial number, () → ByteArray
            val uHex = u.joinToString("") { String.format("%02X", it.toInt() and 0xFF) }
            println("[$n] UID: 0x$uHex  (counter at user EEPROM 0x00-0x03)")
            Thread.sleep(intervalMs)
        }
    }
}
