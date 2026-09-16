'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { ADE7953Minimal } = require('../../../src/chips/power/ade7953');

const connection = new I2CConnection(1, 0x38);
const ade = new ADE7953Minimal(connection, 251.0, 30.0);     // Create ADE7953 driver, (connection, voltage_gain=V/V, current_gain=A/V, bus_type='i2c')

setInterval(async () => {
    const v = await ade.voltage();                            // Read bus voltage, () → float V
    const i = await ade.current();                            // Read load current, () → float A
    const p = await ade.activePower();                        // Read active power, () → float W
    const e = await ade.activeEnergy();                       // Read active energy, () → float Wh
    console.log(`V=${v.toFixed(2)} I=${i.toFixed(3)} P=${p.toFixed(2)} E=${e.toFixed(4)}`);
}, 1000);