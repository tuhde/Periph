'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { MCP4728Full } = require('../../packages/periph/src/chips/adc_dac/mcp4728');

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

function eq(a, b) {
    return Buffer.isBuffer(a) && Buffer.isBuffer(b) && Buffer.compare(a, b) === 0;
}

function last(connection) {
    return connection.writes[connection.writes.length - 1];
}

async function main() {
    const connection = new I2CConnectionMock();
    const dac = new MCP4728Full(connection);
    checkTrue('init', true);

    // set_voltage(channel=1, 0.5) -> code=2048 (0x800). Multi-Write:
    // byte1=0x42, byte2=0x08, byte3=0x00.
    await dac.set_voltage(1, 0.5);
    checkTrue('set_voltage', eq(last(connection), Buffer.from([0x42, 0x08, 0x00])));

    await dac.set_voltage(1, 2.0);
    checkTrue('set_voltage_clamps_high', eq(last(connection), Buffer.from([0x42, 0x0F, 0xFF])));

    // set_raw(channel=3, code=4095) -> byte1=0x46, byte2=0x0F, byte3=0xFF.
    await dac.set_raw(3, 4095);
    checkTrue('set_raw', eq(last(connection), Buffer.from([0x46, 0x0F, 0xFF])));

    await dac.set_raw(9, 9000);
    checkTrue('set_raw_clamps', eq(last(connection), Buffer.from([0x46, 0x0F, 0xFF])));

    // set_all([0.0, 1.0, 0.5, 0.25]) -> Fast Write, 8 bytes.
    await dac.set_all([0.0, 1.0, 0.5, 0.25]);
    checkTrue('set_all', eq(last(connection), Buffer.from([0x00, 0x00, 0x0F, 0xFF, 0x08, 0x00, 0x04, 0x00])));

    let raised = false;
    try {
        await dac.set_all([0.0, 1.0, 0.5]);
    } catch (e) {
        raised = true;
    }
    checkTrue('set_all_wrong_length_throws', raised);

    // set_voltage_eeprom(channel=2, 0.5, vref=1, gain=2) -> code=2048.
    // Single Write byte1=0x5C, byte2=0x98, byte3=0x00.
    await dac.set_voltage_eeprom(2, 0.5, 1, 2);
    checkTrue('set_voltage_eeprom', eq(last(connection), Buffer.from([0x5C, 0x98, 0x00])));

    // set_raw_eeprom(channel=0, code=4095, vref=0, gain=1) -> byte1=0x58, byte2=0x0F, byte3=0xFF.
    await dac.set_raw_eeprom(0, 4095, 0, 1);
    checkTrue('set_raw_eeprom', eq(last(connection), Buffer.from([0x58, 0x0F, 0xFF])));

    // set_all_eeprom: fractions=[0.0,1.0,0.5,0.25], vrefs=[0,1,0,1], gains=[1,2,1,2].
    await dac.set_all_eeprom([0.0, 1.0, 0.5, 0.25], [0, 1, 0, 1], [1, 2, 1, 2]);
    checkTrue('set_all_eeprom', eq(last(connection),
        Buffer.from([0x50, 0x00, 0x00, 0x9F, 0xFF, 0x08, 0x00, 0x94, 0x00])));

    // set_vref(1, 0, 1, 0) -> byte1 = 0x8A.
    await dac.set_vref(1, 0, 1, 0);
    checkTrue('set_vref', eq(last(connection), Buffer.from([0x8A])));

    // set_gain(1, 2, 1, 2) -> byte1 = 0xC5.
    await dac.set_gain(1, 2, 1, 2);
    checkTrue('set_gain', eq(last(connection), Buffer.from([0xC5])));

    // set_power_down(0, 1, 2, 3) -> byte1=0xA2, byte2=0x58.
    await dac.set_power_down(0, 1, 2, 3);
    checkTrue('set_power_down', eq(last(connection), Buffer.from([0xA2, 0x58])));

    // read(): 24-byte response, no register-select write.
    const buf = Buffer.alloc(24);
    buf[0] = 0x80;
    buf[1] = 0x01;
    buf[2] = 0x23;
    buf[13] = 0x90;
    buf[14] = 0xAB;
    connection.queueRead(buf);
    const result = await dac.read();
    checkTrue('read_length', result.channel.length === 4);
    checkTrue('read_ch_a_code', result.channel[0].code === 0x123);
    checkTrue('read_ch_a_vref', result.channel[0].vref === 0);
    checkTrue('read_ch_a_gain', result.channel[0].gain === 1);
    checkTrue('read_ch_a_power_down', result.channel[0].power_down === 0);
    checkTrue('read_ch_a_eeprom_code', result.channel[0].eeprom_code === 0xAB);
    checkTrue('read_ch_a_eeprom_vref', result.channel[0].eeprom_vref === 1);
    checkTrue('read_ch_a_eeprom_gain', result.channel[0].eeprom_gain === 2);
    checkTrue('read_eeprom_ready', result.eeprom_ready === true);

    connection.queueRead(Buffer.from([0x80]));
    checkTrue('is_eeprom_ready_true', (await dac.is_eeprom_ready()) === true);
    connection.queueRead(Buffer.from([0x00]));
    checkTrue('is_eeprom_ready_false', (await dac.is_eeprom_ready()) === false);

    // software_update()/wake_up()/reset(): General Call commands.
    await dac.software_update();
    checkTrue('software_update', eq(last(connection), Buffer.from([0x00, 0x08])));
    await dac.wake_up();
    checkTrue('wake_up', eq(last(connection), Buffer.from([0x00, 0x09])));
    await dac.reset();
    checkTrue('reset', eq(last(connection), Buffer.from([0x00, 0x06])));

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
