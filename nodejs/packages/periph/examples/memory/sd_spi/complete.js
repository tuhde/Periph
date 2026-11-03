"use strict";
// Wiring: SPI0 (MOSI, MISO, SCK) plus a free GPIO for the card's chip select (default line 25 on /dev/gpiochip0):
// the kernel cannot hold chip select across calls, so the connection drives CS itself (spi-device noChipSelect).
// Override with SPI_BUS, SPI_DEV, GPIOCHIP, CS_LINE. 3.3 V cards only.
const { Default } = require('opengpio');                                               // opengpio Default chip factory
const { SPIConnection } = require('../../../src/connection/spi');                                 // SPIConnection class, (bus, dev, options) → SPIConnection
const { GpioOutputPin } = require('../../../src/connection/output_pin');                          // GpioOutputPin class, (output) → GpioOutputPin
const { SDSPIFull } = require('../../../src/chips/memory/sd_spi');                                      // SDSPIFull class, (connection, options) → SDSPIFull

const SPI_BUS = parseInt(process.env.SPI_BUS || '0', 10);
const SPI_DEV = parseInt(process.env.SPI_DEV || '0', 10);
const GPIOCHIP = process.env.GPIOCHIP || '/dev/gpiochip0';
const CS_LINE = parseInt(process.env.CS_LINE || '25', 10);

const open = () => new SPIConnection(SPI_BUS, SPI_DEV, {                                 // Open SPI bus with GPIO chip select, (busNumber, deviceNumber, mode, maxSpeedHz, csPin) → SPIConnection
    mode: 0,
    maxSpeedHz: 400_000,                                                                 // SD init needs <= 400 kHz; the driver raises it afterwards
    csPin: new GpioOutputPin(Default.output({ chip: GPIOCHIP, line: CS_LINE })),
});

(async () => {
    const sd = new SDSPIFull(open());                                                    // Create SD driver, (connection, options) → SDSPIFull
    await sd.init();                                                                     // Run the SPI-mode init sequence, () → Promise<void>

    console.log('type:', sd.cardType());                                                 // Card generation, () → number
                                                                                         // 1 = SDv1, 2 = SDv2, 3 = SDHC
    console.log(sd.info());                                                              // Geometry and capabilities, () → object
                                                                                         // block size, count, erase unit, name from the CID
    console.log('CID:', (await sd.cid()).toString('hex'));                               // Read CID register, () → Promise<Buffer>
                                                                                         // manufacturer, name, revision, serial, date
    console.log('CSD:', sd.csd().toString('hex'));                                       // CSD register read at init, () → Buffer
    console.log('OCR:', (await sd.ocr()).toString(16).padStart(8, '0'));                 // Read OCR, () → Promise<number>
                                                                                         // voltage window and CCS (block addressing) bit
    console.log('present:', await sd.isPresent());                                       // Card responds, () → Promise<boolean>

    // Use the last 8 blocks of the card as scratch, restoring their contents.
    const scratch = sd.blockCount() - 8;
    const saved = await sd.read(scratch, 8);                                             // Read blocks, (block, count) → Promise<Buffer>
                                                                                         // CMD18 multi-block read for count > 1
    const pattern = Buffer.from(Array.from({ length: 8 * 512 }, (_, i) => (i * 7 + 3) & 0xFF));
    await sd.write(scratch, pattern);                                                    // Write blocks, (block, data) → Promise<void>
                                                                                         // CMD25 multi-block write with stop token
    await sd.sync();                                                                     // Wait until programming is done, () → Promise<void>
    console.log('roundtrip:', (await sd.read(scratch, 8)).equals(pattern) ? 'ok' : 'MISMATCH');

    await sd.setCrc(false);                                                              // Disable CRC checking, (enabled) → Promise<void>
                                                                                         // CMD59 off; data CRC16 is no longer verified
    await sd.setCrc(true);
    console.log('set_speed:', await sd.setSpeed(6_000_000));                             // Change SCK frequency, (hz) → Promise<boolean>
                                                                                         // sets the spidev clock
    await sd.erase(scratch, 8);                                                          // Erase blocks, (block, count) → Promise<void>
                                                                                         // CMD32/33/38; contents afterwards are card dependent
    await sd.write(scratch, saved);                                                      // restore the original contents
    console.log('done');
})();
