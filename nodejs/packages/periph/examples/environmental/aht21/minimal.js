'use strict';

const { I2CTransport } = require('../../../src/transport/i2c');
const { AHT21Minimal } = require('../../../src/chips/environmental/aht21');

const transport = new I2CTransport(1, 0x38);
const aht = new AHT21Minimal(transport);                               // Create AHT21 driver, (transport, addr=0x38) → void

setInterval(() => {
    const r = aht.read();                                              // Trigger measurement, () → { temperature_c, humidity_pct }
    console.log(r.temperature_c, r.humidity_pct);
}, 1000);
