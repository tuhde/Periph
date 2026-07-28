'use strict';

const { SPIConnection } = require('../../../src/connection/spi');
const { MFRC522Minimal } = require('../../../src/chips/rfid/mfrc522');

const connection = new SPIConnection(0, 0, { maxSpeedHz: 1_000_000, mode: 0 });     // Create SPI connection, (bus, device, options)
const mfrc = new MFRC522Minimal(connection);                                       // Create MFRC522 driver, (connection, busType='spi')

(async () => {
    for (let i = 0; i < 10; i++) {
        const present = await mfrc.isCardPresent();                                 // Detect card in field, () → bool
        const uid = await mfrc.readUid();                                          // Read card UID (REQA → anticollision → HLTA), () → Buffer | null
        console.log(`present=${present} uid=${uid ? uid.toString('hex') : 'null'}`);
    }
    await connection.close();
    console.log('===DONE: 0 passed, 0 failed===');
})();
