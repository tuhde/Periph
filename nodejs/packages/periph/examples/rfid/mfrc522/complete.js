'use strict';

const { SPIConnection } = require('../../../src/connection/spi');
const { MFRC522Full } = require('../../../src/chips/rfid/mfrc522');

const connection = new SPIConnection(0, 0, { maxSpeedHz: 1_000_000, mode: 0 });     // Create SPI connection, (bus, device, options)
const mfrc = new MFRC522Full(connection);                                          // Create MFRC522 driver, (connection, busType='spi')

(async () => {
    const v = await mfrc.version();                                                 // Read version register, () → {chipType, version}
                                                                                   // for MFRC522 chipType=0x09, version=1 (v1.0) or 2 (v2.0)
    console.log(`MFRC522 chipType=0x${v.chipType.toString(16)} version=${v.version}`);

    const ok = await mfrc.selfTest();                                               // Run digital self test, () → bool
                                                                                   // compares 64 FIFO bytes against the version-specific reference
    console.log(`self_test: ${ok ? 'PASS' : 'FAIL'}`);

    await mfrc.antennaOn();                                                        // Enable antenna driver (TX1+TX2), () → void
    await mfrc.setAntennaGain(38);                                                  // Set receiver gain, (dB=18/23/33/38/43/48) → void
                                                                                   // 38 dB gives better read range on most antennas
    console.log(`current gain: ${await mfrc.antennaGain()} dB`);                    // Read receiver gain, () → int dB

    await mfrc.reset();                                                            // Soft reset and reinitialise, () → void
                                                                                   // re-runs the full initialization sequence

    const uid = await mfrc.selectCard();                                           // Anticollision/Select (leaves card active), () → Buffer | null
    if (uid) {
        console.log(`UID: ${uid.toString('hex')}`);
        const factoryKey = Buffer.from([0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF]);       // well-known default key — see spec
        if (await mfrc.authenticate(4, MFRC522Full.KEY_A, factoryKey, uid.subarray(0, 4))) { // Run MFAuthent, (block, keyType, key=6 B, uid=4 B) → bool
            const block = await mfrc.readBlock(4);                                  // Read 16-byte block, (blockAddress) → Buffer
                                                                                   // requires successful authenticate for the containing sector
            if (block) console.log(`block 4: ${block.toString('hex')}`);
            await mfrc.decrementValue(4, 1);                                        // Decrement value block, (block, delta=uint32) → bool
                                                                                   // runs Decrement + Transfer to the same block
            await mfrc.stopCrypto();                                                // Clear MFCrypto1On, () → void
                                                                                   // required before authenticating a different sector
        }
        await mfrc.haltCard();                                                     // Send HLTA, () → void
    }

    await connection.close();
    console.log('===DONE: 0 passed, 0 failed===');
})();
