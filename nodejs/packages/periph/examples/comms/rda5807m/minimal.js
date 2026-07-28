'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { RDA5807MMinimal } = require('../../../src/chips/comms/rda5807m');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x10', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
const fm = new RDA5807MMinimal(connection, 100.0, 8);   // Create RDA5807M driver, (connection, frequencyMhz=100.0, volume=8)

setInterval(async () => {
    const freq = await fm.seek(true);   // Seek to next station, (up=true) → number|null
    if (freq !== null) console.log(`${freq} MHz`);
}, 3000);
