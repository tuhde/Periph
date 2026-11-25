'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { MAX17048Minimal } = require('../../../src/chips/power/max17048');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function main() {
    const connection = new I2CConnection(1, 0x36);              // Create I²C connection, (bus, addr=0x36) → Connection
    const gauge = new MAX17048Minimal(connection, 1);           // Create MAX17048 driver, (connection, cells)

    while (true) {
        console.log((await gauge.voltage()).toFixed(3), 'V',    // Cell voltage, () → Promise<number> V
                    (await gauge.percent()).toFixed(1), '%');   // State of charge, () → Promise<number> %
        await sleep(1000);
    }
}

main();
