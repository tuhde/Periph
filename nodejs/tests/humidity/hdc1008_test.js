'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { HDC1008Full } = require('../../packages/periph/src/chips/humidity/hdc1008');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x40', 16);

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else           { console.log('FAIL', label); failed++; }
}

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const sensor = new HDC1008Full(connection);

    await sensor.init();
    checkTrue('config after init', (((await sensor.readConfig()) & 0xFF00) === 0x1000));

    const [t, rh] = await sensor.read();
    checkTrue('temperature range', t > -40 && t < 125);
    checkTrue('humidity range', rh >= 0 && rh <= 100);
    checkTrue('readHumidity range', (await sensor.readHumidity()) >= 0);
    checkTrue('readTemperature range', (await sensor.readTemperature()) > -40);
    const [rt, rr] = await sensor.readRaw();
    checkTrue('raw ranges', rt <= 65535 && rr <= 65535);

    for (const [tb, hb] of [[14, 14], [11, 11], [11, 8], [14, 8]]) {
        await sensor.setResolution(tb, hb);
        const [t2, rh2] = await sensor.read();
        checkTrue('read at ' + tb + '/' + hb + ' bit', t2 > -40 && t2 < 125 && rh2 >= 0 && rh2 <= 100);
    }
    await sensor.setResolution(14, 14);

    await sensor.setHeater(true);
    checkTrue('heater on', await sensor.isHeaterOn());
    await sensor.setHeater(false);
    checkTrue('heater off', !(await sensor.isHeaterOn()));
    checkTrue('battery ok', !(await sensor.batteryLow()));
    checkTrue('serial id nonzero', (await sensor.serialId()) !== 0);
    checkTrue('conversion time', sensor.conversionTime() > 0.005 && sensor.conversionTime() < 0.02);
    await sensor.softwareReset();
    checkTrue('config after reset', (((await sensor.readConfig()) & 0xFF00) === 0x1000));

    connection.close();
    console.log('===DONE: ' + passed + ' passed, ' + failed + ' failed===');
    process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });
