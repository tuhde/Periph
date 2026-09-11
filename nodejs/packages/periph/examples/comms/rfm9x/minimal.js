'use strict';

const { SPIConnection } = require('../../../src/connection/spi');
const { RFM95Minimal }  = require('../../../src/chips/comms/rfm9x');

const SPI_BUS  = parseInt(process.env.SPI_BUS || '0', 10);
const SPI_DEV  = parseInt(process.env.SPI_DEV || '0', 10);
const FREQ_HZ  = parseInt(process.env.RFM9X_FREQ || '868000000', 10);

(async () => {
    const connection = new SPIConnection(SPI_BUS, SPI_DEV, { mode: 0, maxSpeedHz: 5_000_000 });   // open SPI bus, (busNumber, deviceNumber, options) → SPIConnection
    const radio = new RFM95Minimal(connection, FREQ_HZ);                                          // create RFM95W driver, (connection, frequencyHz=868e6) → RFM95Minimal
    await radio.init();                                                                            // initialise the chip, () → Promise<void>
                                                                                                  // FSK SLEEP → LoRa SLEEP → STDBY; resets FIFO bases + modem defaults

    await radio.send(Buffer.from('hello'));                                                        // send packet, (data=Buffer ≤255 B) → Promise<void>
    console.log('sent');

    await connection.close();
})();
