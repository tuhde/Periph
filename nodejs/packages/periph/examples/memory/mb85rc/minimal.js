'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { MB85RCMinimal } = require('../../../src/chips/memory/mb85rc');

const connection = new I2CConnection(1, 0x50, null, null, 2);                    // Create I2C connection, (bus=1, addr=0x50, intPin=null, enPin=null, regBytes=2) → Connection
const fram = new MB85RCMinimal(connection);                                      // Create MB85RC driver, (connection) → void

let counter = 0;
setInterval(async () => {
    await fram.writeByte(0x0000, counter);                                       // Write a single byte, (address 0–32767, value 0–255) → void
    const value = await fram.readByte(0x0000);                                   // Read a single byte, (address 0–32767) → number
    console.log('Stored:', value);
    counter = (counter + 1) & 0xFF;
}, 1000);
