'use strict';

const { I2CTransport } = require('../../../src/transport/i2c');
const { APDS9960Minimal } = require('../../../src/chips/light/apds9960');

const transport = new I2CTransport(1, 0x39);
const apds = new APDS9960Minimal(transport);               // Create APDS9960 driver, (transport) → APDS9960Minimal

setInterval(() => {
    const { clear, red, green, blue } = apds.color();      // Read all RGBC channels, () → { clear, red, green, blue }
    console.log(`C=${clear} R=${red} G=${green} B=${blue}`);
}, 1000);
