'use strict';
const { I2CConnection } = require('../../../src/connection/i2c');
const { L3G4200DMinimal } = require('../../../src/chips/gyroscope/l3g4200d');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x68', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
const gyro = new L3G4200DMinimal(connection);             // Create L3G4200D driver, (connection, busType='i2c')

(async () => {
    for (let i = 0; i < 10; i++) {
        const [x, y, z] = await gyro.angularRate();       // Read X/Y/Z angular rate, () → [number, number, number] rad/s
        console.log(`X=${x.toFixed(3)} Y=${y.toFixed(3)} Z=${z.toFixed(3)} rad/s`);
        await new Promise(r => setTimeout(r, 100));
    }
    await connection.close();
    console.log('===DONE: 0 passed, 0 failed===');
})();
