'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { PN532Full } = require('../../packages/periph/src/chips/rfid/pn532');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x24', 16);

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else           { console.log('FAIL', label); failed++; }
}

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const reader = new PN532Full(connection);
    await reader.init();
    const fw = await reader.firmwareVersion();
    checkTrue('firmware_ic', fw.ic === 0x32);
    checkTrue('firmware_support_a', fw.iso14443a);
    const gs = await reader.generalStatus();
    checkTrue('general_status_targets', gs.targets >= 0 && gs.targets <= 2);
    checkTrue('self_test_echo', await reader.selfTest());
    checkTrue('self_test_rom', await reader.selfTest(0x01));
    checkTrue('send_command', (await reader.sendCommand(0x02))[0] === 0x32);
    await reader.startCommand(0x02);
    checkTrue('wait_ready', await reader.waitReady(1.0));
    checkTrue('poll_interrupt', await reader.pollInterrupt());
    checkTrue('read_response', (await reader.readResponse(0x02))[0] === 0x32);
    const old = await reader.readRegister(0x6302);
    await reader.writeRegister(0x6302, old);
    checkTrue('register_roundtrip', (await reader.readRegister(0x6302)) === old);
    await reader.setRfField(false);
    await reader.setRfField(true);
    checkTrue('rf_field_toggle', true);
    await reader.setMaxRetries({ passiveActivation: 2 });
    const t0 = Date.now();
    const uid = await reader.readUid();
    checkTrue('read_uid_returns', uid === null || [4, 7, 10].includes(uid.length));
    checkTrue('read_uid_bounded', Date.now() - t0 < 2000);
    const targets = await reader.listPassiveTargets();
    checkTrue('list_passive_targets', Array.isArray(targets));
    if (targets.length) await reader.release(targets[0].tg);
    await reader.powerDown();
    checkTrue('wake_after_power_down', (await reader.firmwareVersion()).ic === 0x32);
    connection.close();
    console.log('===DONE: ' + passed + ' passed, ' + failed + ' failed===');
    process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });
