'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { AHT10Minimal } = require('../../../src/chips/environmental/aht10');

const connection = new I2CConnection(1, 0x38);
const aht = new AHT10Minimal(connection);                               // Create AHT10 driver, (connection, addr=0x38) → void

setInterval(async () => {
    const r = await aht.read();                                        // Trigger measurement, () → { temperature_c, humidity_pct }
    console.log(r.temperature_c, r.humidity_pct);
}, 1000);
