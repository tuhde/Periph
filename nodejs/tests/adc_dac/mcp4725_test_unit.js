'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { MCP4725Full } = require('../../packages/periph/src/chips/adc_dac/mcp4725');

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

function eq(a, b) {
    return Buffer.isBuffer(a) && Buffer.isBuffer(b) && Buffer.compare(a, b) === 0;
}

async function main() {
    const connection = new I2CConnectionMock();
    const dac = new MCP4725Full(connection);
    checkTrue('init', true);

    // set_voltage(0.5) -> code=2048 (0x800), PD=00. Fast Write byte1=0x08, byte2=0x00.
    await dac.set_voltage(0.5);
    checkTrue('set_voltage', eq(connection.writes[connection.writes.length - 1], Buffer.from([0x08, 0x00])));

    await dac.set_voltage(2.0);
    checkTrue('set_voltage_clamps_high', eq(connection.writes[connection.writes.length - 1], Buffer.from([0x0F, 0xFF])));
    await dac.set_voltage(-1.0);
    checkTrue('set_voltage_clamps_low', eq(connection.writes[connection.writes.length - 1], Buffer.from([0x00, 0x00])));

    // set_raw(4095) -> byte1=0x0F, byte2=0xFF.
    await dac.set_raw(4095);
    checkTrue('set_raw', eq(connection.writes[connection.writes.length - 1], Buffer.from([0x0F, 0xFF])));

    await dac.set_raw(5000);
    checkTrue('set_raw_clamps', eq(connection.writes[connection.writes.length - 1], Buffer.from([0x0F, 0xFF])));

    // set_voltage_eeprom(0.5) -> code=2048. Write DAC+EEPROM: byte1=0x60, byte2=0x80, byte3=0x00.
    await dac.set_voltage_eeprom(0.5);
    checkTrue('set_voltage_eeprom', eq(connection.writes[connection.writes.length - 1], Buffer.from([0x60, 0x80, 0x00])));

    // set_raw_eeprom(4095) -> byte2=0xFF, byte3=0xF0.
    await dac.set_raw_eeprom(4095);
    checkTrue('set_raw_eeprom', eq(connection.writes[connection.writes.length - 1], Buffer.from([0x60, 0xFF, 0xF0])));

    // read(): rdy_bsy=1, por=1, pd_dac=2, code=0x123, eeprom byte4=0x40
    // (0100_0000) -> PD1:PD0 at bits 6:5 = 2, eeprom_code=0xAB.
    connection.setRegister(0x00, [0xC8, 0x12, 0x30, 0x40, 0xAB]);
    const data = await dac.read();
    checkTrue('read_code', data.code === 0x123);
    checkTrue('read_voltage_fraction', Math.abs(data.voltage_fraction - (0x123 / 4095.0)) < 1e-9);
    checkTrue('read_power_down', data.power_down === 2);
    checkTrue('read_eeprom_code', data.eeprom_code === 0xAB);
    checkTrue('read_eeprom_power_down', data.eeprom_power_down === 2);
    checkTrue('read_eeprom_ready', data.eeprom_ready === true);

    // set_power_down(2): reads current 2-byte DAC code (0x0AB), Fast Writes with PD=2.
    connection.setRegister(0x00, [0x00, 0xAB]);
    await dac.set_power_down(2);
    checkTrue('set_power_down', eq(connection.writes[connection.writes.length - 1], Buffer.from([0x20, 0xAB])));

    connection.setRegister(0x00, [0x00, 0x00]);
    await dac.set_power_down(9);
    checkTrue('set_power_down_clamps', eq(connection.writes[connection.writes.length - 1], Buffer.from([0x30, 0x00])));

    // wake_up() / reset(): General Call commands.
    await dac.wake_up();
    checkTrue('wake_up', eq(connection.writes[connection.writes.length - 1], Buffer.from([0x00, 0x09])));
    await dac.reset();
    checkTrue('reset', eq(connection.writes[connection.writes.length - 1], Buffer.from([0x00, 0x06])));

    // is_eeprom_ready(): RDY/BSY bit.
    connection.setRegister(0x00, [0x80]);
    checkTrue('is_eeprom_ready_true', (await dac.is_eeprom_ready()) === true);
    connection.setRegister(0x00, [0x00]);
    checkTrue('is_eeprom_ready_false', (await dac.is_eeprom_ready()) === false);

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
