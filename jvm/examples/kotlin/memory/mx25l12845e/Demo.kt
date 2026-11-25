///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT

// Wiring: SPI0 (MOSI, MISO, SCK, CE0) to the MX25L12845E, 3.3 V. Override with SPI_BUS, SPI_DEVICE.
// All erases and writes stay inside the last 64 KiB of the chip (0xFF0000..0xFFFFFF).
import it.uhde.periph.connection.SPIConnection
import it.uhde.periph.chips.memory.Mx25l12845eFull
import java.nio.ByteBuffer
import java.nio.ByteOrder

const val SCRATCH = 0xFF0000

fun ByteArray.hex() = joinToString(" ") { "%02x".format(it) }

fun main() {
    val bus = System.getenv("SPI_BUS")?.toIntOrNull() ?: 0
    val dev = System.getenv("SPI_DEVICE")?.toIntOrNull() ?: 0

    SPIConnection(bus, dev, 0, 8_000_000).use { connection ->                    // Create SPI connection, (bus, dev, mode=0, maxSpeedHz=8e6) → SPIConnection
        // --- Bring up the chip ---
        // Plain single-I/O SPI: the constructor releases deep power-down, checks the JEDEC ID and
        // waits until the chip is idle. The serial-number area is the factory ESN on parts that have one.
        val flash = Mx25l12845eFull(connection)                                  // Create MX25L12845E driver and check the ID, (connection) → Mx25l12845eFull
        println("MX25L12845E, ${flash.size() / (1024 * 1024)} MiB, serial ${flash.serialNumber().hex()}")   // Read the serial-number OTP area, () → ByteArray(16)

        // --- Append records to a ring of four sectors ---
        // 32-byte records (sequence, tick, payload) fill a sector, 128 per sector. A blank slot
        // (all 0xFF) needs no erase, so appending is just a page program; only when the writer
        // enters the next sector is that sector erased first - one erase per 128 records.
        val ring = 0xFFC000
        val sectors = 4
        val slotLen = 32
        val slots = sectors * 4096 / slotLen
        for (s in 0 until sectors) flash.eraseSector(ring + s * 4096)            // Erase one 4 KiB sector, (address) → Unit
        val t0 = System.nanoTime()
        var erases = 0
        var slot = 0
        for (seq in 0 until 300) {
            if (slot % 128 == 0 && seq >= slots) {                               // wrapped into the next sector: make it blank
                flash.eraseSector(ring + (slot / 128) * 4096)
                erases++
            }
            val rec = ByteArray(slotLen) { 0xA5.toByte() }
            ByteBuffer.wrap(rec).order(ByteOrder.LITTLE_ENDIAN).putInt(0, seq).putInt(4, ((System.nanoTime() - t0) / 1_000_000).toInt())
            flash.pageProgram(ring + slot * slotLen, rec)                        // Program one record (inside a page), (address, data) → Unit
            slot = (slot + 1) % slots
        }
        println("appended 300 records in ${(System.nanoTime() - t0) / 1_000_000} ms, $erases sector erases")

        // --- Verify by scanning the ring ---
        // Every slot must carry a valid sequence number and the 0xA5 payload.
        var good = 0
        var newest = 0
        val all = flash.readBytes(ring, sectors * 4096)                          // Read bytes, (address, length) → ByteArray
        val bb = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN)
        for (s in 0 until slots) {
            val seq = bb.getInt(s * slotLen)
            if (seq != -1 && all[s * slotLen + 20] == 0xA5.toByte()) {
                good++
                newest = maxOf(newest, seq)
            }
        }
        println("ring holds $good valid records, newest sequence number $newest")

        // --- Leave the chip clean ---
        for (s in 0 until sectors) flash.eraseSector(ring + s * 4096)
        println("ring erased")
    }
}
