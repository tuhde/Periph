'use strict';

const spi = require('spi-device');
const { SPIConnection } = require('../../../src/connection/spi');
const { HY3131Minimal } = require('../../../src/chips/adc_dac/hy3131');

const SPI_BUS = parseInt(process.env.SPI_BUS || '0', 10);
const SPI_DEV = parseInt(process.env.SPI_DEV || '0', 10);

(async () => {
    const connection = new SPIConnection(SPI_BUS, SPI_DEV, { mode: spi.MODE0, maxSpeedHz: 1_000_000 });   // Create SPI connection, (bus=0, device=0, mode=0, maxSpeedHz=1_000_000) → SPIConnection
    const adc = await HY3131Minimal.create(connection);                                                    // Create and initialise HY3131 driver, (connection, vref=1.2 V) → HY3131Minimal

    for (;;) {
        const v = await adc.readVoltage();                                                                 // Read AD1 differential voltage, () → float V
        console.log(v.toFixed(5));
    }
})();
