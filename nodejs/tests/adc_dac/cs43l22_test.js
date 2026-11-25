'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { CS43L22Full, GAIN } = require('../../packages/periph/src/chips/adc_dac/cs43l22');

const I2C_BUS = parseInt(process.env.I2C_BUS || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x4A', 16);

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const dac = new CS43L22Full(connection);
    await dac.init();
    checkTrue('init', true);
    checkTrue('read_id', (await dac.readId()).chipId === 0x1C);

    await dac.setVolume(-20);
    checkTrue('volume round trip', Math.abs((await dac.getVolume()) - -20) < 0.01);
    await dac.setVolumeChannels(-10, -12);
    checkTrue('volume channels', (await dac.readRegister(0x20)) === 0xEC && (await dac.readRegister(0x21)) === 0xE8);
    await dac.setHeadphoneGain(GAIN.G_1_000);
    checkTrue('headphone gain', (await dac.headphoneGain()) === 1.0);
    await dac.mute(true);
    checkTrue('mute', ((await dac.readRegister(0x0F)) & 0xC0) === 0xC0);
    await dac.mute(false);
    checkTrue('unmute', ((await dac.readRegister(0x0F)) & 0xC0) === 0);
    await dac.setTone(6, 6);
    checkTrue('tone', (await dac.readRegister(0x1F)) === 0x44);
    await dac.setLimiter(true, { maxDb: -6, cushionDb: -12 });
    checkTrue('limiter', ((await dac.readRegister(0x28)) & 0x80) === 0x80);
    await dac.setLimiter(false);
    checkTrue('speaker status readable', 'spkrHpPin' in (await dac.readSpeakerStatus()));
    await dac.stop();
    checkTrue('stop powers down', (await dac.readRegister(0x02)) === 0x9F);

    console.log(`Passed: ${passed}  Failed: ${failed}`);
    process.exit(failed ? 1 : 0);
}

main();
