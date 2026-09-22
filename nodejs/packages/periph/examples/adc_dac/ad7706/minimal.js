'use strict';

const spi = require('spi-device');
const { SPIConnection } = require('../../../src/connection/spi');
const { AD7706Minimal } = require('../../../src/chips/adc_dac/ad7706');

const SPI_BUS  = parseInt(process.env.SPI_BUS  || '0', 10);
const SPI_DEV  = parseInt(process.env.SPI_DEV  || '0', 10);

const connection = new SPIConnection(SPI_BUS, SPI_DEV, { mode: spi.MODE3, maxSpeedHz: 5_000_000 });   // Create SPI connection, (bus=0, device=0, mode=3, maxSpeedHz=5_000_000) → SPIConnection
const adc = new AD7706Minimal(connection, 2.5, 2_457_600);                                           // Create AD7706 driver, (connection, vref=2.5 V, mclk_hz=2_457_600 Hz) → AD7706Minimal

(async () => {
    const v = await adc.readVoltage();                                                                 // Read Channel 1 voltage, () → float V
    console.log(v.toFixed(4));
})();
