'use strict';

const { SPITransport } = require('../../../src/transport/spi');
const { MFRC522Full } = require('../../../src/chips/rfid/mfrc522');

const transport = new SPITransport(0, 0, { maxSpeedHz: 1_000_000, mode: 0 });
const mfrc = new MFRC522Full(transport);

// --- Prepaid-card credit counter ---
// Simulates a transit-gate / vending-machine credit system using a MIFARE
// Classic value block. The factory default key A (FF FF FF FF FF FF) is
// used for the demo only — replace with a per-deployment secret in any
// real access-control system.
const CREDITS_BLOCK = 4;
const INITIAL_CREDITS = 10;

// --- Detect a card and select it for authenticated access ---
const uid = mfrc.selectCard();                                                    // Anticollision/Select only, () → Buffer | null
if (!uid) {
    console.log('no card in field');
} else {
    // --- Authenticate with the well-known MIFARE factory default key A ---
    // In a real deployment this would be a per-card key stored securely
    // (e.g. diversified per card UID and held in an HSM or secure element).
    const factoryKey = Buffer.from([0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF]);
    if (!mfrc.authenticate(CREDITS_BLOCK, MFRC522Full.KEY_A, factoryKey, uid.subarray(0, 4))) { // MFAuthent, (block, key, uid) → bool
        console.log('authentication failed');
    } else {
        // --- Read the current value block; initialise it if unprogrammed ---
        let block = mfrc.readBlock(CREDITS_BLOCK);                                 // Read 16-byte block, (blockAddress) → Buffer
        const allZero = block && block.every((b) => b === 0);
        if (allZero) {
            const valueBlock = Buffer.alloc(16);
            valueBlock.writeUInt32LE(INITIAL_CREDITS, 0);
            const v = INITIAL_CREDITS;
            valueBlock.writeUInt32LE((~v) >>> 0, 4);
            valueBlock.writeUInt32LE(INITIAL_CREDITS, 8);
            valueBlock[12] = CREDITS_BLOCK;
            valueBlock[13] = (~CREDITS_BLOCK) & 0xFF;
            valueBlock[14] = CREDITS_BLOCK;
            valueBlock[15] = (~CREDITS_BLOCK) & 0xFF;
            mfrc.writeBlock(CREDITS_BLOCK, valueBlock);                            // Write 16 bytes, (block, data=16 B) → bool
            mfrc.restoreValue(CREDITS_BLOCK);                                     // Restore + Transfer, (block) → bool
                                                                                   // normalises the value-block layout
        }

        // --- "Spend" one credit; refuse if balance is zero ---
        block = mfrc.readBlock(CREDITS_BLOCK);                                    // Read current value, (block) → Buffer
        if (block) {
            const credits = block.readUInt32LE(0);
            if (credits <= 0) {
                console.log('Access denied — no credits remaining');
            } else {
                mfrc.decrementValue(CREDITS_BLOCK, 1);                            // Decrement + Transfer, (block, delta) → bool
                const updated = mfrc.readBlock(CREDITS_BLOCK);                     // Read updated value, (block) → Buffer
                if (updated) {
                    const newBalance = updated.readUInt32LE(0);
                    console.log(`spent 1 credit — remaining: ${newBalance}`);
                }
            }
        }
        mfrc.stopCrypto();                                                        // Clear MFCrypto1On, () → void
    }
    mfrc.haltCard();                                                              // Send HLTA, () → void
}

transport.close();
console.log('===DONE: 0 passed, 0 failed===');
