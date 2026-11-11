'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { ADS7828Minimal, ADS7828Full } = require('../../packages/periph/src/chips/adc_dac/ads7828');

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

const close = (a, b) => Math.abs(a - b) < 1e-9;
const last = (conn) => Array.from(conn.writes[conn.writes.length - 1]).join(',');
const writesOf = (conn) => conn.writes.map((w) => Array.from(w).join(',')).join(';');

async function rejects(fn) {
    try { fn(); return false; } catch (e) { return true; }
}

async function main() {
    // Internal reference: init() sends the lone reference-on command 0x08.
    let conn = new I2CConnectionMock();
    let adc = new ADS7828Full(conn);
    checkTrue('constructor_no_traffic', conn.writes.length === 0);
    await adc.init();
    checkTrue('init_enables_internal_reference', writesOf(conn) === '8');
    checkTrue('init_internal_vref', adc.getReference() === 2.5 && adc.usesInternalReference());

    // readRaw(0): SE, C=0, PD=11 -> 0x8C; bytes 0x0A 0xBC -> 0xABC.
    conn.setRegister(0x8C, [0x0A, 0xBC]);
    checkTrue('readRaw_ch0', (await adc.readRaw(0)) === 0xABC);
    checkTrue('readRaw_ch0_command', last(conn) === '140');

    // Interleaved encoding: CH1 -> 0xCC, CH2 -> 0x9C, CH7 -> 0xFC.
    conn.setRegister(0xCC, [0x0F, 0xFF]);
    checkTrue('readRaw_ch1', (await adc.readRaw(1)) === 0xFFF);
    checkTrue('readRaw_ch1_command', last(conn) === '204');
    await adc.readRaw(2);
    checkTrue('readRaw_ch2_command', last(conn) === '156');
    await adc.readRaw(7);
    checkTrue('readRaw_ch7_command', last(conn) === '252');

    // Upper nibble of byte0 is masked off.
    conn.setRegister(0x8C, [0xF1, 0x23]);
    checkTrue('readRaw_masks_high_nibble', (await adc.readRaw(0)) === 0x123);

    // Out-of-range channels clamp.
    await adc.readRaw(99);
    checkTrue('readRaw_clamps_high', last(conn) === '252');
    await adc.readRaw(-3);
    checkTrue('readRaw_clamps_low', last(conn) === '140');

    conn.setRegister(0x8C, [0x08, 0x00]);
    checkTrue('readVoltage', close(await adc.readVoltage(0), 0.5 * 2.5));
    checkTrue('readAll_length', (await adc.readAll()).length === 8);

    // Differential: pair 0 -> 0x0C; pair 3 inverted -> 0x7C.
    conn.setRegister(0x0C, [0x04, 0x00]);
    checkTrue('readDifferentialRaw', (await adc.readDifferentialRaw(0)) === 0x400);
    checkTrue('readDifferential_command', last(conn) === '12');
    await adc.readDifferentialRaw(3, true);
    checkTrue('readDifferential_inverted_command', last(conn) === '124');
    checkTrue('readDifferential_voltage', close(await adc.readDifferential(0), 0x400 / 4096 * 2.5));

    // Signed differential = normal - inverted.
    conn.setRegister(0x0C, [0x00, 0x00]);
    conn.setRegister(0x4C, [0x02, 0x00]);
    checkTrue('readSignedDifferential_negative', close(await adc.readSignedDifferential(0), -0x200 / 4096 * 2.5));
    conn.setRegister(0x0C, [0x02, 0x00]);
    conn.setRegister(0x4C, [0x00, 0x00]);
    checkTrue('readSignedDifferential_positive', close(await adc.readSignedDifferential(0), 0x200 / 4096 * 2.5));

    checkTrue('lsb', close(adc.lsb(), 2.5 / 4096));
    conn.setRegister(0x8C, [0x00, 0x01]);
    conn.setRegister(0xCC, [0x00, 0x02]);
    const scan = await adc.readScan([0, 1]);
    checkTrue('readScan', scan[0] === 1 && scan[1] === 2);

    // setPowerMode validation.
    checkTrue('setPowerMode_rejects_pd1_clear', await rejects(() => adc.setPowerMode(1)));
    checkTrue('setPowerMode_rejects_out_of_range', await rejects(() => adc.setPowerMode(7)));
    adc.setPowerMode(ADS7828Full.REF_ON_ADC_OFF);
    await adc.readRaw(0);
    checkTrue('setPowerMode_applied', last(conn) === '136');
    adc.setPowerMode(ADS7828Full.REF_ON_ADC_ON);

    // powerDown: lone 0x00; next read re-enables the reference first.
    await adc.powerDown();
    checkTrue('powerDown_command', last(conn) === '0');
    await adc.readRaw(0);
    checkTrue('read_after_powerDown_reenables_reference', Array.from(conn.writes[conn.writes.length - 2]).join(',') === '8');

    // External reference: no init traffic, PD=00, scaling by vref.
    conn = new I2CConnectionMock();
    adc = new ADS7828Full(conn, 5.0);
    await adc.init();
    checkTrue('external_no_init_traffic', conn.writes.length === 0);
    checkTrue('external_reference', adc.getReference() === 5.0 && !adc.usesInternalReference());
    conn.setRegister(0x80, [0x08, 0x00]);
    checkTrue('external_readVoltage', close(await adc.readVoltage(0), 2.5));
    checkTrue('external_command_pd0', last(conn) === '128');
    adc.setPowerMode(ADS7828Full.POWER_DOWN_BETWEEN);
    checkTrue('external_allows_pd0', true);

    // setReference switches at run time.
    await adc.setReference(null);
    checkTrue('setReference_internal_enables', last(conn) === '8' && adc.getReference() === 2.5);
    await adc.setReference(3.3);
    checkTrue('setReference_external', adc.getReference() === 3.3 && !adc.usesInternalReference());
    await adc.readRaw(0);
    checkTrue('setReference_external_pd0', last(conn) === '128');

    // Minimal stands alone; a read before init() enables the reference lazily.
    conn = new I2CConnectionMock();
    const m = new ADS7828Minimal(conn);
    checkTrue('minimal_class', (await m.readRaw(0)) === 0 && writesOf(conn) === '8;140');

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
