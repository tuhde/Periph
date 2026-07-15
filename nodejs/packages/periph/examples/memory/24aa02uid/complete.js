'use strict';

const { I2CTransport } = require('../../../src/transport/i2c');
const { EEPROM24AA02UIDFull } = require('../../../src/chips/memory/_24aa02uid');

const transport = new I2CTransport(1, 0x50);
const eeprom = new EEPROM24AA02UIDFull(transport);                             // Create 24AA02UID driver, (transport, addr=0x50) → void

const uid = eeprom.readUid();                                                    // Read 32-bit unique serial number, () → Buffer
                                                                                  // reads 4 bytes at 0xFC-0xFF
console.log('UID bytes:', uid.toString('hex').toUpperCase());
const uidInt = uid.readUInt32BE(0);
console.log('UID int:  ', uidInt);

const mfr = eeprom.readManufacturerCode();                                       // Read manufacturer code, () → number
                                                                                  // reads 0xFA; expect 0x29 (Microchip)
const dev = eeprom.readDeviceCode();                                             // Read device code, () → number
                                                                                  // reads 0xFB; expect 0x41
console.log('MFR: 0x' + mfr.toString(16).toUpperCase() + '  DEV: 0x' + dev.toString(16).toUpperCase());

const first = eeprom.readByte(0x00);                                             // Read a single byte, (address=0x00-0x7F) → number
                                                                                  // random read at user EEPROM address
console.log('First byte: 0x' + first.toString(16).toUpperCase());

eeprom.writeByte(0x10, 0xA5);                                                    // Write a single byte, (address, value) → void
                                                                                  // byte write + ACK-poll until complete (max 5 ms)
const verify = eeprom.readByte(0x10);                                           // Read a single byte, (address=0x00-0x7F) → number
console.log('Wrote 0xA5, read back: 0x' + verify.toString(16).toUpperCase());

const block = eeprom.read(0x20, 8);                                              // Sequential read, (address, length) → Buffer
                                                                                  // reads 8 bytes starting at address
console.log('Block @ 0x20: ' + block.toString('hex'));

eeprom.writePage(0x40, Buffer.from([0x01, 0x02, 0x03, 0x04]));                    // Page write, (address, data<=8 bytes) → void
                                                                                  // writes up to 8 bytes within one page; ACK-polls when done
eeprom.write(0x44, Buffer.from([0xAA, 0xBB, 0xCC, 0xDD, 0xEE]));                // Arbitrary-length write, (address, data) → void
                                                                                  // splits at 8-byte page boundaries; ACK-polls each chunk
console.log('Multi-page write complete');

transport.close();
