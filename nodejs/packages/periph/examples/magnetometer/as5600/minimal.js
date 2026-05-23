'use strict';

const { I2CTransport } = require('../../src/transport/i2c');
const { AS5600Minimal } = require('../../src/chips/magnetometer/as5600');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x36', 16);

const transport = new I2CTransport(I2C_BUS, I2C_ADDR);
const as5600 = new AS5600Minimal(transport);               // Create AS5600 driver, (transport) → AS5600Minimal

setInterval(() => {
    const a = as5600.angle();                              // Read absolute angle, () → float degrees
    const r = as5600.angleRaw();                           // Read scaled angle count, () → int 0-4095
    console.log('angle=%.2f°  raw=%d', a, r);
}, 1000);
