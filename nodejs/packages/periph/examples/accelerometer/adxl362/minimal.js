'use strict';

const { SPIConnection } = require('../../../src/connection/spi');                    // SPIConnection class, (bus, dev, options) → SPIConnection
const { ADXL362Minimal } = require('../../../src/chips/accelerometer/adxl362');      // ADXL362Minimal class, (connection) → ADXL362Minimal

const SPI_BUS = parseInt(process.env.SPI_BUS || '0', 10);
const SPI_DEV = parseInt(process.env.SPI_DEV || '0', 10);

(async () => {
    const connection = new SPIConnection(SPI_BUS, SPI_DEV, { mode: 0, maxSpeedHz: 8_000_000 });    // Open SPI bus, (busNumber, deviceNumber, mode=0, maxSpeedHz=8e6) → SPIConnection
    const chip = new ADXL362Minimal(connection);                                                    // Create ADXL362 driver, (connection) → ADXL362Minimal
                                                                                                    // (init() runs synchronously here — but this constructor returns the instance before init finishes; the read loop below awaits it implicitly via the first register access)

    while (true) {
        const { x, y, z } = await chip.read();                                                      // Read 3-axis acceleration, () → Promise<{x:float, y:float, z:float}> g
        console.log(`x=${x.toFixed(3)}  y=${y.toFixed(3)}  z=${z.toFixed(3)} g`);
        await new Promise(r => setTimeout(r, 100));
    }

    await connection.close();
})();