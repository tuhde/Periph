'use strict';
const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { L3G4200DMinimal, L3G4200DFull } = require('../../packages/periph/src/chips/gyroscope/l3g4200d');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x68', 16);

let passed = 0, failed = 0;

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);

    const gyro = new L3G4200DMinimal(connection);
    if (gyro) { console.log('PASS init'); passed++; }
    else { console.log('FAIL init'); failed++; }

    const [x, y, z] = await gyro.angularRate();
    console.log(`PASS angular_rate x=${x.toFixed(3)} y=${y.toFixed(3)} z=${z.toFixed(3)} rad/s`);
    passed++;

    const gyroFull = new L3G4200DFull(connection);
    if (gyroFull) { console.log('PASS full_init'); passed++; }
    else { console.log('FAIL full_init'); failed++; }

    const cid = await gyroFull.whoAmI();
    if (cid === 0xD3) { console.log('PASS who_am_i'); passed++; }
    else { console.log('FAIL who_am_i'); failed++; }

    await gyroFull.configure(1, 0, 500);
    if (gyroFull._fullScale === 500) { console.log('PASS configure'); passed++; }
    else { console.log('FAIL configure'); failed++; }

    const status = await gyroFull.status();
    console.log(`PASS status 0x${status.toString(16)}`);
    passed++;

    const temp = await gyroFull.temperature();
    if (temp >= -50 && temp <= 100) { console.log('PASS temperature_range'); passed++; }
    else { console.log('FAIL temperature_range'); failed++; }

    await connection.close();
    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
