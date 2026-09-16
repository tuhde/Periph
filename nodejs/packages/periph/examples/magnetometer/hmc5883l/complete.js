'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { HMC5883LFull } = require('../../../src/chips/magnetometer/hmc5883l');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x1E', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
const hmc5883l = new HMC5883LFull(connection);                    // Create HMC5883L driver, (connection) → HMC5883LFull

async function main() {
    // --- Identification ---
    const [idA, idB, idC] = await hmc5883l.identify();           // Read ID registers, () → (int, int, int)
    console.log('ID: 0x%s 0x%s 0x%s', idA.toString(16), idB.toString(16), idC.toString(16));

    // --- Status ---
    console.log('Status: 0x%s', (await hmc5883l.status()).toString(16));  // Read raw status, () → int
    console.log('Data ready:', await hmc5883l.dataReady());                // Check data ready, () → bool

    // --- Magnetic field readings ---
    const { x, y, z } = await hmc5883l.magneticField();           // Read magnetic field, () → { x: float T, y: float T, z: float T }
    console.log('X=%.6f T  Y=%.6f T  Z=%.6f T', x, y, z);

    // --- Configuration ---
    await hmc5883l.configure(15, 8, 1);                            // Configure chip, (odr 0.75-75 Hz, averaging 1/2/4/8, gain 0-7) → None
                                                                   // writes Config A and B registers
    await hmc5883l.setGain(2);                                     // Set gain, (gain 0-7) → None
                                                                   // updates GN bits in Config B
    await hmc5883l.setMode('single');                              // Set operating mode, ('continuous'|'single'|'idle') → None
                                                                   // writes MD bits in Mode Register

    // --- Single-shot measurement ---
    await new Promise(r => setTimeout(r, 6));
    const single = await hmc5883l.singleMeasurement();             // Single-shot measurement, () → { x: float T, y: float T, z: float T }
                                                                   // writes single-measurement mode, waits 6 ms, reads all axes
    console.log('Single: X=%.6f T  Y=%.6f T  Z=%.6f T', single.x, single.y, single.z);

    await hmc5883l.setMode('continuous');                          // Set operating mode, ('continuous'|'single'|'idle') → None

    // --- Self-test ---
    const selfTest = await hmc5883l.selfTest(true);                // Self-test with positive bias, (positive=bool) → { x: float T, y: float T, z: float T }
                                                                   // configures bias, takes measurement, restores normal mode
    console.log('Self-test: X=%.6f T  Y=%.6f T  Z=%.6f T', selfTest.x, selfTest.y, selfTest.z);
}

main().catch(console.error);