'use strict';
const { SpiTransport } = require('../../src/transport/spi');
const { RFM95Minimal } = require('../../src/chips/comms/rfm9x');

const spiBus = parseInt(process.env.SPI_BUS || '0', 10);
const spiDevice = parseInt(process.env.SPI_DEVICE || '0', 10);
const csPin = parseInt(process.env.SPI_CS || '0', 10);

const transport = new SpiTransport(spiBus, spiDevice, csPin);
const rfm = new RFM95Minimal(transport, 868000000);           // Create RFM95 driver, (transport, frequency_hz=868 MHz)

const ver = rfm.version();                                     // Read silicon revision, () → int
console.log('version: 0x' + ver.toString(16));

rfm.send(Buffer.from('Hello'));                               // Transmit packet, (data: Buffer) → void
console.log('sent');

rfm.standby();                                                // Enter STANDBY mode, () → void

rfm.sleep();                                                  // Enter SLEEP mode, () → void

transport.close();
console.log('===DONE: 1 passed, 0 failed===');