'use strict';

const { I2CTransport } = require('../../../src/transport/i2c');
const { INA3221Minimal } = require('../../../src/chips/power/ina3221');

const transport = new I2CTransport(1, 0x40);
const ina = new INA3221Minimal(transport);             // Create INA3221 driver, (transport, r_shunt=0.1 Ω)

setInterval(() => {
    for (const ch of [1, 2, 3]) {
        const v = ina.voltage(ch);                      // Read bus voltage, (channel) → float V
        const i = ina.current(ch);                      // Read load current, (channel) → float A
        const p = ina.power(ch);                        // Read power, (channel) → float W
        console.log(`ch${ch}: ${v.toFixed(3)}V ${i.toFixed(4)}A ${p.toFixed(4)}W`);
    }
}, 1000);