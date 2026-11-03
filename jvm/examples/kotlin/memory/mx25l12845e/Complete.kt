///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT

// Wiring: SPI0 (MOSI, MISO, SCK, CE0) to the MX25L12845E, 3.3 V. Override with SPI_BUS, SPI_DEVICE.
// All erases and writes stay inside the last 64 KiB of the chip (0xFF0000..0xFFFFFF).
import it.uhde.periph.connection.SPIConnection
import it.uhde.periph.chips.memory.Mx25l12845eFull

const val SCRATCH = 0xFF0000

fun ByteArray.hex() = joinToString(" ") { "%02x".format(it) }

fun main() {
    val bus = System.getenv("SPI_BUS")?.toIntOrNull() ?: 0
    val dev = System.getenv("SPI_DEVICE")?.toIntOrNull() ?: 0

    SPIConnection(bus, dev, 0, 8_000_000).use { connection ->                    // Create SPI connection, (bus, dev, mode=0, maxSpeedHz=8e6) → SPIConnection
        val flash = Mx25l12845eFull(connection)                                  // Create MX25L12845E driver and check the ID, (connection, autoErase=false) → Mx25l12845eFull

        val id = flash.jedecId()                                                 // Read JEDEC ID, () → ByteArray
                                                                                 // C2 20 18 for the MX25L12845E
        val res = flash.electronicId()                                           // Read the old-style electronic ID (RES), () → Int
        val ids = flash.deviceIds()                                              // Read manufacturer + device ID (REMS), () → IntArray(2)
        println("JEDEC %s, RES %02X, REMS %02X %02X".format(id.hex(), res, ids[0], ids[1]))

        val sr = flash.readStatus()                                              // Read the status register, () → Int
        val bp = flash.protection()                                              // Read BP3:0, () → Int
        val scur = flash.readSecurity()                                          // Read the security register, () → Int
        println("status 0x%02X, BP=%d, security 0x%02X".format(sr, bp, scur))
        println("ESN: ${flash.serialNumber().hex()}")                            // Read the serial-number OTP area, () → ByteArray(16)
                                                                                 // the factory ESN when security bit 0 is set, blank (FF) otherwise
        println("SFDP: ${flash.readSfdp(0, 16).hex()}")                          // Read the SFDP table, (address, length) → ByteArray
                                                                                 // starts with the signature "SFDP"

        flash.eraseSector(SCRATCH)                                               // Erase one 4 KiB sector, (address) → Unit
                                                                                 // takes 60 ms typ, 300 ms max
        val page = ByteArray(256) { it.toByte() }
        flash.pageProgram(SCRATCH, page)                                         // Program one page (must not cross a page boundary), (address, data) → Unit
                                                                                 // 1.4 ms typ for 256 bytes; bits only go 1 -> 0
        val back = flash.fastRead(SCRATCH, 256)                                  // FAST_READ, (address, length) → ByteArray
                                                                                 // same data as readBytes, for clocks above 50 MHz
        println("page program + fast_read: ${page.contentEquals(back)}")

        flash.eraseBlock32k(SCRATCH)                                             // Erase a 32 KiB block, (address) → Unit
                                                                                 // 0.5 s typ
        flash.eraseBlock64k(SCRATCH)                                             // Erase a 64 KiB block, (address) → Unit
                                                                                 // 0.7 s typ
        println("after block erase: ${flash.readBytes(SCRATCH, 16).hex()}")      // Read bytes, (address, length) → ByteArray
        // chipErase() (80 s typ, wipes all 16 MiB) is deliberately not called here.

        val text = "erase-aware write".toByteArray()
        flash.writeBytes(SCRATCH + 100, text)                                    // Write bytes, erasing the sector only when needed, (address, data) → Unit
        flash.writeBytes(SCRATCH + 100, text)                                    // the same bytes again: nothing is programmed or erased
        println("text: ${String(flash.readBytes(SCRATCH + 100, text.size))}")

        flash.deepPowerDown()                                                    // Enter deep power-down, () → Unit
                                                                                 // only RDP / RES are accepted afterwards
        flash.releaseDeepPowerDown()                                             // Leave deep power-down (RDP, waits tRES2), () → Unit
        println("busy: ${flash.isBusy()}")                                       // WIP bit, () → Boolean
        flash.waitUntilReady(1000)                                               // Wait for WIP = 0, (timeoutMs) → Unit

        println(flash.info())                                                    // Geometry and capabilities, () → BlockInfo
                                                                                 // 512-byte blocks, 4 KiB erase unit, needsErase
        // setProtection(), writeStatus(), programOtp() and lockOtp() change non-volatile state; see the spec before using them.

        flash.eraseSector(SCRATCH)
        println("done")
    }
}
