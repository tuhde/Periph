///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-groovy:1.0-SNAPSHOT

import it.uhde.periph.connection.SPIConnection
import it.uhde.periph.connection.SysfsOutputPin
import it.uhde.periph.chips.memory.SdSpiMinimal

// Wiring: SPI bus plus a free GPIO for the card's chip select (env CS_GPIO, a sysfs GPIO number): the kernel
// cannot hold chip select across calls, so the connection drives CS itself (SPI_NO_CS).
int bus = System.getenv("SPI_BUS")?.toInteger() ?: 0
int dev = System.getenv("SPI_DEVICE")?.toInteger() ?: 0
int csGpio = System.getenv("CS_GPIO")?.toInteger() ?: 25   // sysfs GPIO number wired to the card's CS

def cs = new SysfsOutputPin(csGpio)                                                    // Create CS pin, (gpioNumber=25) → SysfsOutputPin
def connection = new SPIConnection(bus, dev, 0, 400_000, cs)                           // Create SPI connection with GPIO chip select, (bus, dev, mode=0, maxSpeedHz=400e3, csPin) → SPIConnection
try {
    def sd = new SdSpiMinimal(connection)                                              // Create SD driver and init the card, (connection) → SdSpiMinimal

    println "capacity: ${sd.blockCount()} blocks = ${sd.blockCount().intdiv(2048)} MiB"   // Capacity in 512-byte blocks, () → long
    5.times {
        byte[] block = sd.readBlock(0)                                                 // Read one 512-byte block, (block) → byte[]
        println "block 0: ${block[0..3].collect { String.format('%02X', it) }.join(' ')} ... signature ${String.format('%02X%02X', block[510], block[511])}"
        Thread.sleep(2000)
    }
} finally {
    connection.close()
    cs.close()
}
