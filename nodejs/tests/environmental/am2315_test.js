'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { AM2315Full } = require('../../packages/periph/src/chips/environmental/am2315');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x5C', 16);

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else           { console.log('FAIL', label); failed++; }
}

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const sensor = new AM2315Full(connection);

    const info = await sensor.readDeviceInfo();
    checkTrue('device info model', info.model !== 0xFFFF);

    const r = await sensor.read();
    checkTrue('temperature range', r.temperature_c >= -40 && r.temperature_c <= 125);
    checkTrue('humidity range', r.humidity_pct >= 0 && r.humidity_pct <= 100);
    checkTrue('readHumidity range', (await sensor.readHumidity()) <= 100);
    checkTrue('readTemperature range', (await sensor.readTemperature()) >= -40);
    const [rh, rt] = await sensor.readRaw();
    checkTrue('raw ranges', rh <= 1000 && (rt & 0x7FFF) <= 1250);
    const f = await sensor.readFresh();
    checkTrue('readFresh range', f.temperature_c >= -40 && f.humidity_pct <= 100);
    checkTrue('readRegisters length', (await sensor.readRegisters(0x00, 4)).length === 4);

    await sensor.writeUserRegisters(0x1234, 0xABCD);
    checkTrue('user registers round trip', JSON.stringify(await sensor.readUserRegisters()) === JSON.stringify([0x1234, 0xABCD]));
    await sensor.writeUserRegisters(0, 0);
    await sensor.wake();

    connection.close();
    console.log('===DONE: ' + passed + ' passed, ' + failed + ' failed===');
    process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });
