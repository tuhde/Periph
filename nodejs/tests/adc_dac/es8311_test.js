'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { ES8311Full }    = require('../../packages/periph/src/chips/adc_dac/es8311');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x18', 16);

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else           { console.log('FAIL', label); failed++; }
}

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const dac = new ES8311Full(connection);
    await dac._ready;
    checkTrue('init accepted', true);

    await dac.setVolume(-20.0);
    checkTrue('setVolume/getVolume round trip', Math.abs((await dac.getVolume()) - (-20.0)) < 0.01);

    await dac.setMicGain(24);
    checkTrue('setMicGain/getMicGain round trip', Math.abs((await dac.getMicGain()) - 24.0) < 0.01);

    await dac.setMute(true);
    checkTrue('DAC mute bits set', ((await dac.readRegister(0x31)) & 0x60) === 0x60);
    await dac.setMute(false);
    checkTrue('DAC mute bits cleared', ((await dac.readRegister(0x31)) & 0x60) === 0x00);

    await dac.configureClock(12288000, 16000);
    checkTrue('configureClock accepted', true);

    await dac.configureSerial({ format: 'i2s', wordLength: 16 });
    checkTrue('configureSerial reads back format/word length', ((await dac.readRegister(0x09)) & 0x1F) === 0x0C);

    await dac.setAdcVolume(0);
    checkTrue('ADC volume 0 dB (0xBF)', (await dac.readRegister(0x17)) === 0xBF);

    await dac.configureAlc(true, { maxLevelDb: -12.0, minLevelDb: -18.1, automute: true, noiseGateDb: -60 });
    checkTrue('ALC enabled bit', ((await dac.readRegister(0x18)) & 0x80) === 0x80);
    await dac.configureAlc(false);

    await dac.configureDrc(true, { maxLevelDb: -12.0, minLevelDb: -24.1 });
    checkTrue('DRC enabled bit', ((await dac.readRegister(0x34)) & 0x80) === 0x80);
    await dac.configureDrc(false);

    await dac.setLoopback(true);
    checkTrue('loopback bit set', ((await dac.readRegister(0x44)) & 0x80) === 0x80);
    await dac.setLoopback(false);

    await dac.setAdcdatMode('dac_adc');
    checkTrue('ADCDAT mode dac_adc (4)', (((await dac.readRegister(0x44)) >> 4) & 7) === 4);
    await dac.setAdcdatMode('adc_adc');

    await dac.setDacRamp(8);
    checkTrue('DAC ramp rate 8', ((await dac.readRegister(0x37)) >> 4) === 8);

    await dac.powerDown();
    checkTrue('powerDown clears CSM_ON', ((await dac.readRegister(0x00)) & 0x80) === 0x00);
    await dac.reset();
    checkTrue('reset accepted', true);

    await connection.close();
    console.log('===DONE: ' + passed + ' passed, ' + failed + ' failed===');
    process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });
