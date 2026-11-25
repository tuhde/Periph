'use strict';

const { UARTConnection } = require('../../packages/periph/src/connection/uart');
const { TMC2209Full } = require('../../packages/periph/src/chips/motor/tmc2209');

const UART_PORT = process.env.UART_PORT || '/dev/ttyS0';
const UART_BAUD = parseInt(process.env.UART_BAUD || '115200', 10);

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function main() {
    const connection = new UARTConnection(UART_PORT, { baudRate: UART_BAUD, timeoutMs: 100 });
    await connection.open();
    const motor = new TMC2209Full(connection);
    await motor.init();
    checkTrue('init', true);

    const ioin = await motor.readIoin();
    checkTrue('ioin_version', ioin.version === 0x21);
    await motor.setStallThreshold(60);
    checkTrue('sg_result_even', (await motor.readStallguard()) % 2 === 0);
    const before = await motor.readIfcnt();
    await motor.setCurrent(0.5);
    checkTrue('ifcnt_advances', (await motor.readIfcnt()) === ((before + 2) & 0xFF));
    await motor.setMicrosteps(16);
    checkTrue('mres_readback', (((await motor.readRegister(0x6C)) >>> 24) & 0xF) === 4);
    checkTrue('gconf_pdn_disable', ((await motor.readRegister(0x00)) & 0x40) === 0x40);
    await motor.enable();
    await motor.run(100);
    await sleep(1000);
    checkTrue('moving', !(await motor.isStandstill()));
    checkTrue('tstep_nonzero', (await motor.readTstep()) > 0);
    checkTrue('no_fault', (await motor.readFault()) === 0);
    await motor.stop();
    await sleep(500);
    await motor.disable();
    checkTrue('status_decoded', 'cs_actual' in (await motor.readStatus()));

    await connection.close();
    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed ? 1 : 0);
}

main().catch((e) => { console.error(e); process.exit(1); });
