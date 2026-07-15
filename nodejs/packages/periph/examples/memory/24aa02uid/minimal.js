'use strict';

const { I2CTransport } = require('../../../src/transport/i2c');
const { EEPROM24AA02UIDMinimal } = require('../../../src/chips/memory/_24aa02uid');

const transport = new I2CTransport(1, 0x50);
const eeprom = new EEPROM24AA02UIDMinimal(transport);                          // Create 24AA02UID driver, (transport, addr=0x50) → void

setInterval(() => {
    const uid = eeprom.readUid();                                                // Read 32-bit unique serial number, () → Buffer
    console.log('UID:', uid.toString('hex').toUpperCase());
}, 2000);
