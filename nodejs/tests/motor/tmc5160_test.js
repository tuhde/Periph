'use strict';

const { SPIConnection } = require('../../packages/periph/src/connection/spi');
const { GpioOutputPin } = require('../../packages/periph/src/connection/output_pin');
const { Default } = require('opengpio');
const { TMC5160Full } = require('../../packages/periph/src/chips/motor/tmc5160');

const SPI_BUS = parseInt(process.env.SPI_BUS || '0', 10);
const SPI_DEV = parseInt(process.env.SPI_DEV || '0', 10);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

async function main() {
    const spi = new SPIConnection(SPI_BUS, SPI_DEV, { mode: 3, maxSpeedHz: 1_000_000 });
    const enPin = new GpioOutputPin(Default.output({ chip: 0, line: parseInt(process.env.EN_LINE || '17', 10) }));
    const motor = new TMC5160Full(spi, { enPin });
    await motor.init();
    checkTrue('init', true);

    const ioin = await motor.readIoin();
    checkTrue('ioin_version', ioin.version === 0x30);
    checkTrue('ioin_not_stepdir', !ioin.sd_mode);
    await motor.writeRegister(0x13, 600, true);
    checkTrue('verified_write', true);
    await motor.setCurrent(1.0);
    await motor.setPosition(0);
    checkTrue('position_zero', (await motor.position()) === 0);
    await motor.enable();
    await motor.move(50);
    checkTrue('position_reached', await motor.isPositionReached());
    checkTrue('position_50', Math.abs((await motor.position()) - 50) < 1);
    await motor.run(100);
    await sleep(500);
    checkTrue('velocity_nonzero', (await motor.velocity()) > 0);
    await motor.stop();
    await motor.waitUntilReached();
    checkTrue('standstill_after_stop', (await motor.velocity()) === 0);
    checkTrue('no_fault', (await motor.readFault()) === 0);
    checkTrue('tstep_readable', (await motor.readTstep()) >= 0);
    await motor.setStallThreshold(0);
    const sg = await motor.readStallguard();
    checkTrue('sg_result_range', sg >= 0 && sg <= 1023);
    await motor.disable();
    checkTrue('status_object', 'cs_actual' in (await motor.readStatus()));
    await spi.close();

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed ? 1 : 0);
}

main();
