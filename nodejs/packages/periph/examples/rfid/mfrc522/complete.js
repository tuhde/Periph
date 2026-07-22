'use strict';

const { SPITransport } = require('../../../src/transport/spi');
const { MFRC522Full } = require('../../../src/chips/rfid/mfrc522');

const transport = new SPITransport(0, 0, { maxSpeedHz: 1_000_000, mode: 0 });     // Create SPI transport, (bus, device, options)
const mfrc = new MFRC522Full(transport);                                          // Create MFRC522 driver, (transport, busType='spi')

const v = mfrc.version();                                                         // Read version register, () → {chipType, version}
                                                                                   // for MFRC522 chipType=0x09, version=1 (v1.0) or 2 (v2.0)
console.log(`MFRC522 chipType=0x${v.chipType.toString(16)} version=${v.version}`);

const ok = mfrc.selfTest();                                                       // Run digital self test, () → bool
                                                                                   // compares 64 FIFO bytes against the version-specific reference
console.log(`self_test: ${ok ? 'PASS' : 'FAIL'}`);

mfrc.antennaOn();                                                                 // Enable antenna driver (TX1+TX2), () → void
mfrc.setAntennaGain(38);                                                          // Set receiver gain, (dB=18/23/33/38/43/48) → void
                                                                                   // 38 dB gives better read range on most antennas
console.log(`current gain: ${mfrc.antennaGain()} dB`);                            // Read receiver gain, () → int dB

mfrc.reset();                                                                     // Soft reset and reinitialise, () → void
                                                                                   // re-runs the full initialization sequence

const uid = mfrc.selectCard();                                                    // Anticollision/Select (leaves card active), () → Buffer | null
if (uid) {
    console.log(`UID: ${uid.toString('hex')}`);
    const factoryKey = Buffer.from([0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF]);          // well-known default key — see spec
    if (mfrc.authenticate(4, MFRC522Full.KEY_A, factoryKey, uid.subarray(0, 4))) {// Run MFAuthent, (block, keyType, key=6 B, uid=4 B) → bool
        const block = mfrc.readBlock(4);                                          // Read 16-byte block, (blockAddress) → Buffer
                                                                                   // requires successful authenticate for the containing sector
        if (block) console.log(`block 4: ${block.toString('hex')}`);
        mfrc.decrementValue(4, 1);                                                // Decrement value block, (block, delta=uint32) → bool
                                                                                   // runs Decrement + Transfer to the same block
        mfrc.stopCrypto();                                                        // Clear MFCrypto1On, () → void
                                                                                   // required before authenticating a different sector
    }
    mfrc.haltCard();                                                              // Send HLTA, () → void
}

transport.close();
console.log('===DONE: 0 passed, 0 failed===');
