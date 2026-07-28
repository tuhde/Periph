'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { INA3221Minimal } = require('../../../src/chips/power/ina3221');

const connection = new I2CConnection(1, 0x40);
const ina = new INA3221Minimal(connection);             // Create INA3221 driver, (connection, r_shunt=0.1 Ω)

setInterval(async () => {
    for (const ch of [1, 2, 3]) {
        const v = await ina.voltage(ch);                 // Read bus voltage, (channel) → float V
        const i = await ina.current(ch);                 // Read load current, (channel) → float A
        const p = await ina.power(ch);                   // Read power, (channel) → float W
        console.log(`ch${ch}: ${v.toFixed(3)}V ${i.toFixed(4)}A ${p.toFixed(4)}W`);
    }
}, 1000);
