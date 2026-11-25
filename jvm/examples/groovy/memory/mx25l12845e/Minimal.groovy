///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-groovy:1.0-SNAPSHOT

// Wiring: SPI0 (MOSI, MISO, SCK, CE0) to the MX25L12845E, 3.3 V. Override with SPI_BUS, SPI_DEVICE.
// All erases and writes stay inside the last 64 KiB of the chip (0xFF0000..0xFFFFFF).
import it.uhde.periph.connection.SPIConnection
import it.uhde.periph.chips.memory.Mx25l12845eMinimal

final int SCRATCH = 0xFF0000
def hex = { byte[] b -> b.collect { String.format('%02x', it & 0xFF) }.join(' ') }

int bus = System.getenv("SPI_BUS")?.toInteger() ?: 0
int dev = System.getenv("SPI_DEVICE")?.toInteger() ?: 0

try (SPIConnection connection = new SPIConnection(bus, dev, 0, 8_000_000)) {    // Create SPI connection, (bus, dev, mode=0, maxSpeedHz=8e6) → SPIConnection
    Mx25l12845eMinimal flash = new Mx25l12845eMinimal(connection)               // Create MX25L12845E driver and check the ID, (connection) → Mx25l12845eMinimal

    println "JEDEC ID ${hex(flash.jedecId())}, ${flash.size().intdiv(1024 * 1024)} MiB"   // Read JEDEC ID, () → byte[]; capacity in bytes, () → int
    10.times { int counter ->
        flash.writeBytes(SCRATCH, [(byte) counter] as byte[])                   // Write bytes (erases the sector when needed), (address, data) → void
        int value = flash.readBytes(SCRATCH, 1)[0] & 0xFF                       // Read bytes, (address, length) → byte[]
        println String.format("wrote 0x%02X, read 0x%02X", counter, value)
        Thread.sleep(500)
    }
    flash.eraseSector(SCRATCH)                                                  // Erase one 4 KiB sector, (address) → void
    println "done, scratch sector erased"
}
