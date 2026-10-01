'use strict';
const { discover } = require('../../src/discovery/discovery');

const I2C_BUS = parseInt(process.env.I2C_BUS || '1', 10);

(async () => {
    const devices = await discover(I2C_BUS);           // Discover chips, (bus=1, {active=false}) → DiscoveredDevice[]
    for (const dev of devices) {
        console.log('0x' + dev.address.toString(16), dev.identified || dev.candidates);
    }
    console.log('===DONE: 0 passed, 0 failed===');
})();
