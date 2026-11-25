///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT

import it.uhde.periph.connection.SPIConnection
import it.uhde.periph.connection.SysfsOutputPin
import it.uhde.periph.chips.memory.SdSpiFull

// Wiring: SPI bus plus a free GPIO for the card's chip select (env CS_GPIO, a sysfs GPIO number): the kernel
// cannot hold chip select across calls, so the connection drives CS itself (SPI_NO_CS).
fun main() {
    val bus = System.getenv("SPI_BUS")?.toIntOrNull() ?: 0
    val dev = System.getenv("SPI_DEVICE")?.toIntOrNull() ?: 0
    val csGpio = System.getenv("CS_GPIO")?.toIntOrNull() ?: 25   // sysfs GPIO number wired to the card's CS

    SysfsOutputPin(csGpio).use { cs ->                                                  // Create CS pin, (gpioNumber=25) → SysfsOutputPin
        SPIConnection(bus, dev, 0, 400_000, cs).use { connection ->                     // Create SPI connection with GPIO chip select, (bus, dev, mode=0, maxSpeedHz=400e3, csPin) → SPIConnection
            val sd = SdSpiFull(connection)                                              // Create SD driver and init the card, (connection) → SdSpiFull

            println("type: ${sd.cardType()}")                                           // Card generation, () → CardType
            println(sd.info())                                                          // Geometry and capabilities, () → BlockInfo
                                                                                        // block size, count, erase unit, name from the CID
            println("CID: " + sd.cid().joinToString("") { "%02x".format(it) })          // Read CID register, () → ByteArray
                                                                                        // manufacturer, name, revision, serial, date
            println("CSD: " + sd.csd().joinToString("") { "%02x".format(it) })          // CSD register read at init, () → ByteArray
            println("OCR: %08X".format(sd.ocr()))                                       // Read OCR, () → Int
                                                                                        // voltage window and CCS (block addressing) bit
            println("present: ${sd.isPresent()}")                                       // Card responds, () → Boolean

            // Use the last 8 blocks of the card as scratch, restoring their contents.
            val scratch = sd.blockCount() - 8
            val saved = sd.read(scratch, 8)                                             // Read blocks, (block, count) → ByteArray
                                                                                        // CMD18 multi-block read for count > 1
            val pattern = ByteArray(8 * 512) { (it * 7 + 3).toByte() }
            sd.write(scratch, pattern)                                                  // Write blocks, (block, data) → Unit
                                                                                        // CMD25 multi-block write with stop token
            sd.sync()                                                                   // Wait until programming is done, () → Unit
            println("roundtrip: " + if (sd.read(scratch, 8).contentEquals(pattern)) "ok" else "MISMATCH")

            sd.setCrc(false)                                                            // Disable CRC checking, (enabled) → Unit
                                                                                        // CMD59 off; data CRC16 is no longer verified
            sd.setCrc(true)
            sd.setSpeed(6_000_000)                                                      // Change SCK frequency, (hz) → Unit
                                                                                        // sets the spidev clock
            sd.erase(scratch, 8)                                                        // Erase blocks, (block, count) → Unit
                                                                                        // CMD32/33/38; contents afterwards are card dependent
            sd.write(scratch, saved)                                                    // restore the original contents
            println("done")
        }
    }
}
