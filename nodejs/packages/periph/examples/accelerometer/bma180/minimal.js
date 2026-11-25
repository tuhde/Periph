'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { BMA180Minimal } = require('../../packages/periph/src/chips/accelerometer/bma180');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x40', 16);

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const accel = new BMA180Minimal(connection);
    await new Promise(r => setImmediate(r));
    await new Promise(r => setImmediate(r));

    setInterval(async () => {
        const [x, y, z] = await accel.read();             // Read 3-axis acceleration, () → [x, y, z] g
        console.log(`x=${x.toFixed(3)} y=${y.toFixed(3)} z=${z.toFixed(3)} g`);
    }, 100);
}

main().catch(e => { console.error(e); process.exit(2); });