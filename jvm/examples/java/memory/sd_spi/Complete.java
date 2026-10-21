///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.connection.SPIConnection;
import it.uhde.periph.connection.SysfsOutputPin;
import it.uhde.periph.chips.memory.SdSpiFull;
import it.uhde.periph.storage.BlockInfo;

import java.util.Arrays;
import java.util.HexFormat;

// Wiring: SPI bus plus a free GPIO for the card's chip select (env CS_GPIO, a sysfs GPIO number): the kernel
// cannot hold chip select across calls, so the connection drives CS itself (SPI_NO_CS).
public class Complete {
    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("SPI_BUS", "0"));
        int dev = Integer.parseInt(System.getenv().getOrDefault("SPI_DEVICE", "0"));
        int csGpio = Integer.parseInt(System.getenv().getOrDefault("CS_GPIO", "25"));   // sysfs GPIO number wired to the card's CS

        try (var cs = new SysfsOutputPin(csGpio);                                          // Create CS pin, (gpioNumber=25) → SysfsOutputPin
             var connection = new SPIConnection(bus, dev, 0, 400_000, cs)) {              // Create SPI connection with GPIO chip select, (bus, dev, mode=0, maxSpeedHz=400e3, csPin) → SPIConnection
            var sd = new SdSpiFull(connection);                                            // Create SD driver and init the card, (connection) → SdSpiFull

            System.out.println("type: " + sd.cardType());                                  // Card generation, () → CardType
            BlockInfo info = sd.info();                                                    // Geometry and capabilities, () → BlockInfo
                                                                                           // block size, count, erase unit, name from the CID
            System.out.println(info);
            System.out.println("CID: " + HexFormat.of().formatHex(sd.cid()));              // Read CID register, () → byte[]
                                                                                           // manufacturer, name, revision, serial, date
            System.out.println("CSD: " + HexFormat.of().formatHex(sd.csd()));              // CSD register read at init, () → byte[]
            System.out.printf("OCR: %08X%n", sd.ocr());                                    // Read OCR, () → int
                                                                                           // voltage window and CCS (block addressing) bit
            System.out.println("present: " + sd.isPresent());                              // Card responds, () → boolean

            // Use the last 8 blocks of the card as scratch, restoring their contents.
            long scratch = sd.blockCount() - 8;
            byte[] saved = sd.read(scratch, 8);                                            // Read blocks, (block, count) → byte[]
                                                                                           // CMD18 multi-block read for count > 1
            byte[] pattern = new byte[8 * 512];
            for (int i = 0; i < pattern.length; i++) pattern[i] = (byte) (i * 7 + 3);
            sd.write(scratch, pattern);                                                    // Write blocks, (block, data) → void
                                                                                           // CMD25 multi-block write with stop token
            sd.sync();                                                                     // Wait until programming is done, () → void
            System.out.println("roundtrip: " + (Arrays.equals(sd.read(scratch, 8), pattern) ? "ok" : "MISMATCH"));

            sd.setCrc(false);                                                              // Disable CRC checking, (enabled) → void
                                                                                           // CMD59 off; data CRC16 is no longer verified
            sd.setCrc(true);
            sd.setSpeed(6_000_000);                                                        // Change SCK frequency, (hz) → void
                                                                                           // sets the spidev clock
            sd.erase(scratch, 8);                                                          // Erase blocks, (block, count) → void
                                                                                           // CMD32/33/38; contents afterwards are card dependent
            sd.write(scratch, saved);                                                      // restore the original contents
            System.out.println("done");
        }
    }
}
