'use strict';

const { I2CTransport } = require('../../packages/periph/src/transport/i2c');
const { AS5600Full }   = require('../../packages/periph/src/chips/magnetometer/as5600');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x36', 16);

let passed = 0;
let failed = 0;

function checkEq(label, got, expected) {
    if (got === expected) {
        console.log('PASS', label);
        passed++;
    } else {
        console.log('FAIL %s: got %d, expected %d', label, got, expected);
        failed++;
    }
}

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else           { console.log('FAIL', label); failed++; }
}

const transport = new I2CTransport(I2C_BUS, I2C_ADDR);

// --- Magnet status poll (60 s max at 5 Hz) ---
console.log('--- magnet status (60 s max) ---');
const _deadline = Date.now() + 60000;
while (Date.now() < _deadline) {
    const s   = transport.writeRead(Buffer.from([0x0B]), 1)[0];
    const agc = transport.writeRead(Buffer.from([0x1A]), 1)[0];
    const md = !!(s & 0x08), ml = !!(s & 0x10), mh = !!(s & 0x20);
    console.log('MD=%d ML=%d MH=%d AGC=%d', +md, +ml, +mh, agc);
    if (md) break;
    Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 200);
}
console.log('--- end magnet status ---');

const as5600 = Object.create(AS5600Full.prototype);
as5600._transport = transport;

// --- Magnet detection ---
checkTrue('magnet_detected', as5600.isMagnetDetected());

// --- Angle readings ---
const a = as5600.angle();
checkTrue('angle in range 0-360', a >= 0.0 && a < 360.0);

const r = as5600.angleRaw();
checkTrue('angle_raw in range 0-4095', r >= 0 && r <= 4095);

const ra = as5600.rawAngle();
checkTrue('raw_angle in range 0-4095', ra >= 0 && ra <= 4095);

const rad = as5600.rawAngleDegrees();
checkTrue('raw_angle_degrees in range 0-360', rad >= 0.0 && rad < 360.0);

// --- Diagnostics ---
checkTrue('agc non-negative', as5600.agc() >= 0);
checkTrue('magnitude non-negative', as5600.magnitude() >= 0);

// --- Status ---
const sb = as5600.statusByte();
checkTrue('status_byte valid', sb >= 0 && sb <= 255);

// --- Position configuration (volatile) ---
as5600.setZeroPosition(100);
checkEq('zero_position after set', as5600.zeroPosition(), 100);

as5600.setMaxPosition(2000);
checkEq('max_position after set', as5600.maxPosition(), 2000);

as5600.setMaxAngle(2048);
checkEq('max_angle after set', as5600.maxAngle(), 2048);

// --- Configure ---
as5600.configure(0, 0, 0, 0, 0, 0, false);
checkTrue('configure accepted', as5600.isMagnetDetected());

// --- Burn count ---
const bc = as5600.burnCount();
checkTrue('burn_count in range 0-3', bc >= 0 && bc <= 3);

transport.close();

console.log('===DONE: %d passed, %d failed===', passed, failed);
process.exit(failed === 0 ? 0 : 1);
