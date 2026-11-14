'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { HTU21DFFull } = require('../../packages/periph/src/chips/humidity/htu21df');

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
    const sensor = new HTU21DFFull(connection);

    await sensor.init();
    checkTrue('user register after reset', ((await sensor.readUserRegister()) & 0x83) === 0x02);

    const [t, rh] = await sensor.read();
    checkTrue('temperature range', t > -40 && t < 125);
    checkTrue('humidity range', rh >= -6 && rh <= 119);
    checkTrue('readHumidity range', (await sensor.readHumidity()) >= -6);
    checkTrue('readTemperature range', (await sensor.readTemperature()) > -40);
    checkTrue('readHumidityCompensated range', (await sensor.readHumidityCompensated()) >= -6);
    const [rt, rr] = await sensor.readRaw();
    checkTrue('raw ranges', rt <= 65535 && rr <= 65535);

    for (const res of [0, 1, 2, 3]) {
        await sensor.setResolution(res);
        checkTrue('resolution ' + res, (await sensor.getResolution()) === res);
        const [t2, rh2] = await sensor.read();
        checkTrue('read at res ' + res, t2 > -40 && t2 < 125 && rh2 >= -6 && rh2 <= 119);
    }
    await sensor.setResolution(0);

    await sensor.setHeater(true);
    checkTrue('heater on', await sensor.heater());
    await sensor.setHeater(false);
    checkTrue('heater off', !(await sensor.heater()));
    checkTrue('battery ok', !(await sensor.batteryLow()));

    const dp = await sensor.dewPoint();
    checkTrue('dew point plausible', dp > -60 && dp < 60);
    await sensor.softReset();
    checkTrue('read after soft reset', (await sensor.read())[1] >= -6);

    connection.close();
    console.log('===DONE: ' + passed + ' passed, ' + failed + ' failed===');
    process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });
