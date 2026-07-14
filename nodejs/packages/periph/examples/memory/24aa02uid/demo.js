'use strict';

const { I2CTransport } = require('../../../src/transport/i2c');
const { EEPROM24AA02UIDFull } = require('../../../src/chips/memory/_24aa02uid');

const transport = new I2CTransport(1, 0x50);
const eeprom = new EEPROM24AA02UIDFull(transport);                             // Create 24AA02UID driver, (transport, addr=0x50) → void

// --- Read the chip's factory-programmed 32-bit serial number ---
// The UID at 0xFC-0xFF never changes and identifies the device
// across the entire 256-byte address space.
const uid = eeprom.readUid();                                                    // Read 32-bit unique serial number, () → Buffer
                                                                                  // reads 4 bytes at 0xFC-0xFF
console.log('Device UID: 0x' + uid.toString('hex').toUpperCase());
console.log('Device UID int:', uid.readUInt32BE(0));

// --- Maintain a 4-byte boot counter in user EEPROM at 0x00-0x03 ---
// Read the existing value (or zero on a fresh chip), increment,
// write back as 4 big-endian bytes. The user EEPROM is rewritable;
// the UID region above 0x80 is not, so the two stay independent.
const existing = eeprom.read(0x00, 4);                                           // Sequential read, (address, length) → Buffer
                                                                                  // reads 4 bytes from user EEPROM
let counter = existing.readUInt32BE(0);
counter += 1;
const updated = Buffer.alloc(4);
updated.writeUInt32BE(counter, 0);
eeprom.write(0x00, updated);                                                     // Arbitrary-length write, (address, data) → void
                                                                                  // writes 4 bytes; ACK-polls when done
console.log('Boot count:', counter);

let n = 0;
setInterval(() => {
    // --- Loop reading the UID only, showing it never changes ---
    // The two distinct areas of the chip (immutable identification
    // above 0x80, rewritable storage below 0x80) are exercised
    // independently.
    const uid = eeprom.readUid();                                                // Read 32-bit unique serial number, () → Buffer
    console.log('[' + n + '] UID: 0x' + uid.toString('hex').toUpperCase() + '  (counter at user EEPROM 0x00-0x03)');
    n++;
}, 2000);
