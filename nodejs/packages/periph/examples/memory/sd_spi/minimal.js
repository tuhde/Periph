"use strict";
// Wiring: SPI0 (MOSI, MISO, SCK) plus a free GPIO for the card's chip select (default line 25 on /dev/gpiochip0):
// the kernel cannot hold chip select across calls, so the connection drives CS itself (spi-device noChipSelect).
// Override with SPI_BUS, SPI_DEV, GPIOCHIP, CS_LINE. 3.3 V cards only.
const { Default } = require('opengpio');                                               // opengpio Default chip factory
const { SPIConnection } = require('../../../src/connection/spi');                                 // SPIConnection class, (bus, dev, options) → SPIConnection
const { GpioOutputPin } = require('../../../src/connection/output_pin');                          // GpioOutputPin class, (output) → GpioOutputPin
const { SDSPIMinimal } = require('../../../src/chips/memory/sd_spi');                                      // SDSPIMinimal class, (connection, options) → SDSPIMinimal

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
    const sd = new SDSPIMinimal(open());                                                 // Create SD driver, (connection, options) → SDSPIMinimal
    await sd.init();                                                                     // Run the SPI-mode init sequence, () → Promise<void>

    console.log(`capacity: ${sd.blockCount()} blocks = ${sd.blockCount() / 2048} MiB`);  // Capacity in 512-byte blocks, () → number
    while (true) {
        const block = await sd.readBlock(0);                                             // Read one 512-byte block, (block) → Promise<Buffer>
        console.log('block 0:', block.subarray(0, 16).toString('hex'));
        console.log(`boot signature: ${block.subarray(510, 512).toString('hex')}`);
        await new Promise(r => setTimeout(r, 2000));
    }
})();
