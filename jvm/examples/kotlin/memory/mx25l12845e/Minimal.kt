///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT

// Wiring: SPI0 (MOSI, MISO, SCK, CE0) to the MX25L12845E, 3.3 V. Override with SPI_BUS, SPI_DEVICE.
// All erases and writes stay inside the last 64 KiB of the chip (0xFF0000..0xFFFFFF).
import it.uhde.periph.connection.SPIConnection
import it.uhde.periph.chips.memory.Mx25l12845eMinimal

const val SCRATCH = 0xFF0000

fun ByteArray.hex() = joinToString(" ") { "%02x".format(it) }

fun main() {
    val bus = System.getenv("SPI_BUS")?.toIntOrNull() ?: 0
    val dev = System.getenv("SPI_DEVICE")?.toIntOrNull() ?: 0

    SPIConnection(bus, dev, 0, 8_000_000).use { connection ->                    // Create SPI connection, (bus, dev, mode=0, maxSpeedHz=8e6) → SPIConnection
        val flash = Mx25l12845eMinimal(connection)                               // Create MX25L12845E driver and check the ID, (connection) → Mx25l12845eMinimal

        println("JEDEC ID ${flash.jedecId().hex()}, ${flash.size() / (1024 * 1024)} MiB")   // Read JEDEC ID, () → ByteArray; capacity in bytes, () → Int
        for (counter in 0 until 10) {
            flash.writeBytes(SCRATCH, byteArrayOf(counter.toByte()))             // Write bytes (erases the sector when needed), (address, data) → Unit
            val value = flash.readBytes(SCRATCH, 1)[0].toInt() and 0xFF          // Read bytes, (address, length) → ByteArray
            println("wrote 0x%02X, read 0x%02X".format(counter, value))
            Thread.sleep(500)
        }
        flash.eraseSector(SCRATCH)                                               // Erase one 4 KiB sector, (address) → Unit
        println("done, scratch sector erased")
    }
}
