'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { Pca6416aMinimal, Pca6416aFull } =
    require('../../packages/periph/src/chips/io_expander/pca6416a');

const REG_INPUT = 0x00, REG_OUTPUT = 0x02, REG_POLARITY = 0x04, REG_CONFIG = 0x06;

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

const eq = (a, b) => a.length === b.length && a.every((v, i) => v === b[i]);
const dataWrites = (w) => w.filter((x) => x.length > 1).map((x) => x[0]);

async function main() {
    const connection = new I2CConnectionMock();
    const chip = new Pca6416aFull(connection);
    await chip.ready;
    const reg = (r) => connection.registers.get(r);

    // Init writes the reset state of both ports as two-byte pair writes.
    checkTrue('init_output', reg(REG_OUTPUT) === 0xFF && reg(REG_OUTPUT + 1) === 0xFF);
    checkTrue('init_polarity', reg(REG_POLARITY) === 0x00 && reg(REG_POLARITY + 1) === 0x00);
    checkTrue('init_config', reg(REG_CONFIG) === 0xFF && reg(REG_CONFIG + 1) === 0xFF);
    checkTrue('init_shadows', chip._shadow === 0xFFFF && chip._direction === 0xFFFF);
    checkTrue('init_pair_writes', eq(dataWrites(connection.writes), [REG_OUTPUT, REG_POLARITY, REG_CONFIG]));
    checkTrue('init_never_writes_input', !dataWrites(connection.writes).includes(REG_INPUT));

    connection.setRegister(REG_INPUT, [0xA5, 0x5A]);
    checkTrue('read_port_0', (await chip.readPort(0)) === 0xA5);
    checkTrue('read_port_1', (await chip.readPort(1)) === 0x5A);
    checkTrue('read_all', (await chip.readAll()) === 0x5AA5);
    let threw = false;
    try { await chip.readPort(2); } catch (e) { threw = true; }
    checkTrue('read_port_range', threw);

    await chip.writePort(0, 0x3C);
    checkTrue('write_port_0_register', reg(REG_OUTPUT) === 0x3C);
    await chip.writePort(1, 0xC3);
    checkTrue('write_port_1_register', reg(REG_OUTPUT + 1) === 0xC3);
    checkTrue('write_port_shadow', chip._shadow === 0xC33C);

    let before = connection.writes.length;
    await chip.writeAll(0x1234);
    checkTrue('write_all_registers', reg(REG_OUTPUT) === 0x34 && reg(REG_OUTPUT + 1) === 0x12);
    checkTrue('write_all_single_transfer', dataWrites(connection.writes.slice(before)).length === 1);
    checkTrue('write_all_shadow', chip._shadow === 0x1234);

    // Direction: output clears the CONFIG bit of its port, input sets it.
    await chip.writeAll(0xFFFF);
    const pin7 = chip.pin(7);
    await pin7.setDirection('out');
    checkTrue('p07_output', reg(REG_CONFIG) === 0x7F && reg(REG_CONFIG + 1) === 0xFF);
    await pin7.setDirection('in');
    checkTrue('p07_input', reg(REG_CONFIG) === 0xFF);
    const pin12 = chip.pin(12);
    await pin12.setDirection('out');
    checkTrue('p14_output_port1', reg(REG_CONFIG + 1) === 0xEF && reg(REG_CONFIG) === 0xFF);
    await pin12.setDirection('in');
    checkTrue('p14_input_port1', reg(REG_CONFIG + 1) === 0xFF);

    // OUTPUT is written before CONFIG when a pin becomes an output.
    before = connection.writes.length;
    await chip.pin(9).setDirection('out');
    checkTrue('output_before_config', eq(dataWrites(connection.writes.slice(before)), [REG_OUTPUT + 1, REG_CONFIG + 1]));
    await chip.pin(9).setDirection('in');

    await chip.writeAll(0x0000);
    connection.setRegister(REG_INPUT, [0x04, 0x80]);
    checkTrue('pin_read_port0', (await chip.pin(2).read()) === 1);
    checkTrue('pin_read_port1', (await chip.pin(15).read()) === 1);
    checkTrue('pin_read_low', (await chip.pin(3).read()) === 0);
    await chip.pin(0).write(1);
    checkTrue('pin0_on', reg(REG_OUTPUT) === 0x01);
    await chip.pin(2).write(1);
    checkTrue('pin2_on_preserves_pin0', reg(REG_OUTPUT) === 0x05);
    await chip.pin(0).write(0);
    checkTrue('pin0_off_preserves_pin2', reg(REG_OUTPUT) === 0x04);
    await chip.pin(8).write(1);
    checkTrue('pin8_on_port1', reg(REG_OUTPUT + 1) === 0x01 && reg(REG_OUTPUT) === 0x04);

    before = connection.writes.length;
    await chip.configurePolarity(0xF00F);
    checkTrue('configure_polarity', reg(REG_POLARITY) === 0x0F && reg(REG_POLARITY + 1) === 0xF0);
    checkTrue('configure_polarity_single_write', dataWrites(connection.writes.slice(before)).length === 1);

    before = connection.writes.length;
    await chip.setDirection(0xFF00);
    checkTrue('set_direction_registers', reg(REG_CONFIG) === 0x00 && reg(REG_CONFIG + 1) === 0xFF);
    checkTrue('set_direction_order', eq(dataWrites(connection.writes.slice(before)), [REG_OUTPUT, REG_CONFIG]));
    checkTrue('get_direction', chip.getDirection() === 0xFF00);

    checkTrue('read_register', (await chip.readRegister(REG_CONFIG + 1)) === 0xFF);
    await chip.writeRegister(REG_OUTPUT + 1, 0x81);
    checkTrue('write_register_output1_shadow', (chip._shadow >> 8) === 0x81);
    await chip.writeRegister(REG_CONFIG, 0x0F);
    checkTrue('write_register_config0_shadow', chip._direction === 0xFF0F);
    await chip.writeRegister(REG_POLARITY + 1, 0x3C);
    checkTrue('write_register_polarity1_shadow', chip._polarity === 0x3C0F);
    for (const bad of [0, 1, 8]) {
        threw = false;
        try { await chip.writeRegister(bad, 0); } catch (e) { threw = true; }
        checkTrue('write_register_bad_' + bad, threw);
    }
    threw = false;
    try { await chip.readRegister(8); } catch (e) { threw = true; }
    checkTrue('read_register_range', threw);

    // pollInterrupt: 16-bit changed mask limited to inputs, cache updated.
    await chip.setDirection(0xF0F0);
    connection.setRegister(REG_INPUT, [0x00, 0x00]);
    await chip.pollInterrupt();
    connection.setRegister(REG_INPUT, [0x35, 0x9A]);
    checkTrue('poll_interrupt_masks_outputs', (await chip.pollInterrupt()) === 0x9030);
    checkTrue('poll_interrupt_no_change', (await chip.pollInterrupt()) === 0x0000);

    // watch() on an output pin rejects.
    const chip2 = new Pca6416aFull(new I2CConnectionMock());
    await chip2.ready;
    const out10 = chip2.pin(10, 'out');
    threw = false;
    try { await out10.watch(() => {}); } catch (e) { threw = true; }
    checkTrue('watch_output_raises', threw);

    await chip.reset();
    checkTrue('reset_output', reg(REG_OUTPUT) === 0xFF && reg(REG_OUTPUT + 1) === 0xFF);
    checkTrue('reset_polarity', reg(REG_POLARITY) === 0x00 && reg(REG_POLARITY + 1) === 0x00);
    checkTrue('reset_config', reg(REG_CONFIG) === 0xFF && reg(REG_CONFIG + 1) === 0xFF);
    checkTrue('reset_shadows', chip._shadow === 0xFFFF && chip._direction === 0xFFFF);

    const m = new Pca6416aMinimal(new I2CConnectionMock());
    await m.ready;
    checkTrue('minimal_i2c', (await m.readPort(0)) === 0);

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
