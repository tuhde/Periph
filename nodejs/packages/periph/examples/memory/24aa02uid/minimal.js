'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { EEPROM24AA02UIDMinimal } = require('../../../src/chips/memory/_24aa02uid');

const connection = new I2CConnection(1, 0x50);
const eeprom = new EEPROM24AA02UIDMinimal(connection);                          // Create 24AA02UID driver, (connection, addr=0x50) → void

setInterval(async () => {
    const uid = await eeprom.readUid();                                          // Read 32-bit unique serial number, () → Buffer
    console.log('UID:', uid.toString('hex').toUpperCase());
}, 2000);
