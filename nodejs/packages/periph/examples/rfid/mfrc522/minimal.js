'use strict';

const { SPITransport } = require('../../../src/transport/spi');
const { MFRC522Minimal } = require('../../../src/chips/rfid/mfrc522');

const transport = new SPITransport(0, 0, { maxSpeedHz: 1_000_000, mode: 0 });     // Create SPI transport, (bus, device, options)
const mfrc = new MFRC522Minimal(transport);                                       // Create MFRC522 driver, (transport, busType='spi')

for (let i = 0; i < 10; i++) {
    const present = mfrc.isCardPresent();                                         // Detect card in field, () → bool
    const uid = mfrc.readUid();                                                  // Read card UID (REQA → anticollision → HLTA), () → Buffer | null
    console.log(`present=${present} uid=${uid ? uid.toString('hex') : 'null'}`);
}
transport.close();
console.log('===DONE: 0 passed, 0 failed===');
