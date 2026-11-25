'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { BMA180Full } = require('../../packages/periph/src/chips/accelerometer/bma180');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x40', 16);

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const accel = new BMA180Full(connection);              // Create BMA180 full driver, (connection)
    await new Promise(r => setImmediate(r));
    await new Promise(r => setImmediate(r));

    // --- Configure for tilt + tap + free-fall demo at low-noise, 40 Hz, ±2 g ---
    await accel.setBandwidth(40);                           // Set bandwidth, (bandwidth_hz=40 Hz) → None
    await accel.calibrateOffset(0x07, 1);                   // Calibrate offset, (axes=0x07, mode=1 fine) → None

    // --- Arm tap and free-fall detection with latching so we never miss an event ---
    await accel.setTap(0.5, 250);                           // Configure tap, (threshold_g=0.5, window_ms=250) → None
    await accel.setLowG(0.3, 40);                           // Configure low-g, (threshold_g=0.3, duration_ms=40) → None
    await accel.setLatch(true);                             // Set latch, (enabled=True) → None

    // --- Print tilt + temperature every 100 ms; poll interrupts for tap/free-fall ---
    const start = Date.now();
    while (Date.now() - start < 60_000) {
        const [x, y, z] = await accel.read();              // Read 3-axis acceleration, () → [x, y, z] g
        const pitch = Math.atan2(x, Math.sqrt(y*y + z*z)) * 180 / Math.PI;
        const roll  = Math.atan2(y, Math.sqrt(x*x + z*z)) * 180 / Math.PI;
        const mag   = Math.sqrt(x*x + y*y + z*z);
        const t     = await accel.readTemperature();       // Read temperature, () → float °C
        console.log(`pitch=${pitch.toFixed(1)} roll=${roll.toFixed(1)} |a|=${mag.toFixed(3)} g  T=${t.toFixed(1)} C`);

        const flags = await accel.pollInterrupt();         // Poll interrupt, () → int
        if (flags & 0x10) {
            console.log('DOUBLE TAP');
            await accel.clearInterrupt();                  // Clear interrupt, () → None
        }
        if (flags & 0x40) {
            console.log('FREE FALL');
            await accel.clearInterrupt();                  // Clear interrupt, () → None
        }
        await new Promise(r => setTimeout(r, 100));
    }
}

main().catch(e => { console.error(e); process.exit(2); });