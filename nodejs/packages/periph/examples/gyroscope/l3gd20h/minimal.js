const { I2CConnection } = require('../../src/connection/i2c_linux');
const { L3GD20HMinimal } = require('../../src/chips/gyroscope/l3gd20h');

const conn = new I2CConnection(1, 0x6A);
const gyro = new L3GD20HMinimal(conn);

async function loop() {
    const [x, y, z] = await gyro.gyro();  // Read angular rate, () -> [float, float, float] rad/s
    console.log(`x=${x.toFixed(3)} y=${y.toFixed(3)} z=${z.toFixed(3)} rad/s`);
    setTimeout(loop, 100);
}

loop().catch(console.error);