'use strict';

const { I2CTransport } = require('../../src/transport/i2c');
const { AS5600Full } = require('../../src/chips/magnetometer/as5600');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x36', 16);

const transport = new I2CTransport(I2C_BUS, I2C_ADDR);
const as5600 = new AS5600Full(transport);

// --- Motor feedback monitor: read angle 10 times per second ---
// AGC monitoring detects magnet distance drift; status changes alert to magnet removal.
// In 5 V mode, target AGC ≈ 128; in 3.3 V mode, target AGC ≈ 64.

let prevStatus = as5600.statusByte();

for (let n = 0; n < 10; n++) {
    const a = as5600.angle();                              // Read absolute angle, () → float degrees
    const r = as5600.rawAngle();                           // Read raw unscaled angle, () → int 0-4095
    const g = as5600.agc();                                // Read AGC value, () → int

    // --- Check for status changes (magnet inserted/removed) ---
    const status = as5600.statusByte();
    if (status !== prevStatus) {
        if (!as5600.isMagnetDetected()) {
            console.log('[MAGNET REMOVED] MD=0');
        } else {
            console.log('[MAGNET DETECTED] MD=1  MH=%d  ML=%d',
                as5600.isMagnetTooStrong(), as5600.isMagnetTooWeak());
        }
        prevStatus = status;
    }

    // --- AGC health check ---
    if (as5600.isMagnetDetected()) {
        let tag = '[OK]';
        if (g < 64 || g > 192) {
            tag = '[AGC low — magnet weak or too far]';
        }
        console.log('angle=%.2f°  raw=%d  agc=%d  %s', a, r, g, tag);
    }

    // 100 ms synchronous delay
    const end = Date.now() + 100;
    while (Date.now() < end) {}
}

transport.close();
