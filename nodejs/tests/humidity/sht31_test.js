'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { SHT31Full } = require('../../packages/periph/src/chips/humidity/sht31');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x44', 16);

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else           { console.log('FAIL', label); failed++; }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const sensor = new SHT31Full(connection);
    await sensor.init();

    const [t, rh] = await sensor.read();
    checkTrue('temperature range', t > -40 && t < 125);
    checkTrue('humidity range', rh >= 0 && rh <= 100);
    checkTrue('readTemperature range', (await sensor.readTemperature()) > -40);
    checkTrue('readHumidity range', (await sensor.readHumidity()) >= 0);
    const [rt, rr] = await sensor.readRaw();
    checkTrue('readRaw range', rt >= 0 && rt <= 65535 && rr >= 0 && rr <= 65535);

    for (const level of ['low', 'medium', 'high']) {
        sensor.setRepeatability(level);
        const [t2, rh2] = await sensor.read();
        checkTrue('repeatability ' + level, t2 > -40 && t2 < 125 && rh2 >= 0 && rh2 <= 100);
    }

    checkTrue('status readable', (await sensor.readStatus()) >= 0);
    await sensor.clearStatus();
    checkTrue('status bit 4 cleared', ((await sensor.readStatus()) & 0x0010) === 0);

    await sensor.startPeriodic(2, 'high');
    await sleep(1200);
    const res = await sensor.fetch();
    checkTrue('periodic fetch', res !== null && res[0] > -40 && res[0] < 125);
    await sensor.stopPeriodic();

    await sensor.setHeater(true);
    checkTrue('heater bit set', ((await sensor.readStatus()) & 0x2000) !== 0);
    await sensor.setHeater(false);
    checkTrue('heater bit cleared', ((await sensor.readStatus()) & 0x2000) === 0);

    const dp = await sensor.dewPoint();
    checkTrue('dew point plausible', dp > -60 && dp < 60);
    await sensor.softReset();
    checkTrue('read after soft reset', (await sensor.read())[1] >= 0);

    connection.close();
    console.log('===DONE: ' + passed + ' passed, ' + failed + ' failed===');
    process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });
