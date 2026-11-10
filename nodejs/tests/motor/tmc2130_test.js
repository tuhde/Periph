'use strict';

const { SPIConnection } = require('../../packages/periph/src/connection/spi');
const { GpioOutputPin } = require('../../packages/periph/src/connection/output_pin');
const { Default } = require('opengpio');
const { TMC2130Full } = require('../../packages/periph/src/chips/motor/tmc2130');

const SPI_BUS = parseInt(process.env.SPI_BUS || '0', 10);
const SPI_DEV = parseInt(process.env.SPI_DEV || '0', 10);

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

async function main() {
    const spi = new SPIConnection(SPI_BUS, SPI_DEV, { mode: 3, maxSpeedHz: 1_000_000 });
    const out = (line) => new GpioOutputPin(Default.output({ chip: 0, line }));
    const motor = new TMC2130Full(spi, out(parseInt(process.env.STEP_LINE || '27', 10)),
        out(parseInt(process.env.DIR_LINE || '22', 10)), { enPin: out(parseInt(process.env.EN_LINE || '17', 10)) });
    await motor.init();
    checkTrue('init', true);

    const ioin = await motor.readIoin();
    checkTrue('ioin_version', ioin.version === 0x11);
    await motor.writeRegister(0x13, 600, true);
    checkTrue('verified_write', true);
    await motor.setCurrent(0.5);
    await motor.setMicrosteps(16);
    checkTrue('mres_readback', (((await motor.readRegister(0x6C)) >>> 24) & 0xF) === 4);
    checkTrue('gconf_readback', ((await motor.readRegister(0x00)) & 0x4) === 0x4);
    await motor.enable();
    await motor.move(50, 100);
    checkTrue('no_fault', (await motor.readFault()) === 0);
    checkTrue('tstep_readable', (await motor.readTstep()) >= 0);
    await motor.setStallThreshold(0);
    const sg = await motor.readStallguard();
    checkTrue('sg_result_range', sg >= 0 && sg <= 1023);
    await motor.disable();
    checkTrue('status_object', 'cs_actual' in (await motor.readStatus()));
    checkTrue('gstat_object', 'reset' in (await motor.readGlobalStatus()));
    await spi.close();

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed ? 1 : 0);
}

main();
