'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { ADE7953Full } = require('../../packages/periph/src/chips/power/ade7953');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x38', 16);

(async () => {
    let passed = 0;
    let failed = 0;
    function checkTrue(label, condition) {
        if (condition) { console.log('PASS', label); passed++; }
        else { console.log('FAIL', label); failed++; }
    }

    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const ade = new ADE7953Full(connection, 251.0, 30.0);

    checkTrue('voltage non-negative', (await ade.voltage()) >= 0.0);
    checkTrue('current non-negative', (await ade.current()) >= 0.0);
    checkTrue('activePower finite',   (await ade.activePower()) > -1000000.0);
    checkTrue('activeEnergy finite',  (await ade.activeEnergy()) > -1000.0);
    checkTrue('linePeriod positive',  (await ade.linePeriod()) > 0.0);

    await ade.reset();
    checkTrue('voltage after reset', (await ade.voltage()) >= 0.0);

    connection.close();
    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
})();