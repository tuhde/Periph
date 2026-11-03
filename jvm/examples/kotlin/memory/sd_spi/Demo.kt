///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT

import it.uhde.periph.connection.SPIConnection
import it.uhde.periph.connection.SysfsOutputPin
import it.uhde.periph.chips.memory.SdSpiFull
import java.nio.ByteBuffer
import java.nio.ByteOrder

// Wiring: SPI bus plus a free GPIO for the card's chip select (env CS_GPIO, a sysfs GPIO number): the kernel
// cannot hold chip select across calls, so the connection drives CS itself (SPI_NO_CS).
fun main() {
    val bus = System.getenv("SPI_BUS")?.toIntOrNull() ?: 0
    val dev = System.getenv("SPI_DEVICE")?.toIntOrNull() ?: 0
    val csGpio = System.getenv("CS_GPIO")?.toIntOrNull() ?: 25   // sysfs GPIO number wired to the card's CS

    SysfsOutputPin(csGpio).use { cs ->                                                  // Create CS pin, (gpioNumber=25) → SysfsOutputPin
        SPIConnection(bus, dev, 0, 400_000, cs).use { connection ->                     // Create SPI connection with GPIO chip select, (bus, dev, mode=0, maxSpeedHz=400e3, csPin) → SPIConnection
            // --- Bring up the card ---
            // The card starts in SD mode; the driver sends 80 clocks with CS high, CMD0 with CS
            // low to enter SPI mode, then negotiates the card type. SCK runs at 400 kHz during
            // init and is raised to 12 MHz afterwards.
            val sd = SdSpiFull(connection)                                              // Create SD driver and init the card, (connection) → SdSpiFull
            val info = sd.info()                                                        // Geometry and capabilities, () → BlockInfo
            println("card ${info.name()}, ${info.blockCount() / 2048} MiB")

            // --- Log samples to a raw block range (filesystem-free data logger) ---
            // Each 512-byte block holds a header (sequence number, tick) and 126 samples.
            // The last blocks of the card are used and restored afterwards, so a card that
            // carries a filesystem keeps its data (only the tail of the volume is touched).
            val blocks = 64
            val first = sd.blockCount() - blocks
            val saved = sd.read(first, blocks)                                          // Read blocks, (block, count) → ByteArray
            val t0 = System.nanoTime()
            for (seq in 0 until blocks) {
                val b = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN)
                b.putInt(seq).putInt(((System.nanoTime() - t0) / 1_000_000).toInt())
                for (i in 2 until 128) b.putInt(seq * 1000 + i)
                sd.write(first + seq, b.array())                                        // Write blocks, (block, data) → Unit
            }
            sd.sync()                                                                   // Wait until programming is done, () → Unit
            println("wrote $blocks blocks in ${(System.nanoTime() - t0) / 1_000_000} ms")

            // --- Verify by reading back ---
            // Read every block back and recompute the expected sample values; any single
            // mismatch (or a CRC16 error thrown by the driver) is counted.
            var bad = 0
            for (seq in 0 until blocks) {
                val b = ByteBuffer.wrap(sd.read(first + seq, 1)).order(ByteOrder.LITTLE_ENDIAN)   // Read blocks, (block, count) → ByteArray
                var ok = b.getInt() == seq
                b.getInt()
                var i = 2
                while (i < 128 && ok) { ok = b.getInt() == seq * 1000 + i; i++ }
                if (!ok) bad++
            }
            println("verify: $bad bad blocks")

            // --- Restore the original contents ---
            sd.write(first, saved)
            sd.sync()
            println("restored")
        }
    }
}
