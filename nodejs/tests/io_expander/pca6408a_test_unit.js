'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { Pca6408aMinimal, Pca6408aFull } =
    require('../../packages/periph/src/chips/io_expander/pca6408a');

const REG_INPUT = 0x00, REG_OUTPUT = 0x01, REG_POLARITY = 0x02, REG_CONFIG = 0x03;

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
    const chip = new Pca6408aFull(connection);
    await chip.ready;

    checkTrue('init_output', connection.registers.get(REG_OUTPUT) === 0xFF);
    checkTrue('init_polarity', connection.registers.get(REG_POLARITY) === 0x00);
    checkTrue('init_config', connection.registers.get(REG_CONFIG) === 0xFF);
    checkTrue('init_shadows', chip._shadow === 0xFF && chip._direction === 0xFF);
    checkTrue('init_never_writes_input', !dataWrites(connection.writes).includes(REG_INPUT));

    connection.setRegister(REG_INPUT, [0xA5]);
    checkTrue('read_port', (await chip.readPort()) === 0xA5);
    checkTrue('read_port_ignores_port', (await chip.readPort(1)) === 0xA5);

    await chip.writePort(0, 0x3C);
    checkTrue('write_port_register', connection.registers.get(REG_OUTPUT) === 0x3C);
    checkTrue('write_port_shadow', chip._shadow === 0x3C);

    const pin7 = chip.pin(7);
    await pin7.setDirection('out');
    checkTrue('p7_output', connection.registers.get(REG_CONFIG) === 0x7F);
    await pin7.setDirection('in');
    checkTrue('p7_input', connection.registers.get(REG_CONFIG) === 0xFF);

    await chip.writePort(0, 0x00);
    let before = connection.writes.length;
    await chip.pin(1).setDirection('out');
    checkTrue('output_before_config', eq(dataWrites(connection.writes.slice(before)), [REG_OUTPUT, REG_CONFIG]));

    await chip.writePort(0, 0x00);
    connection.setRegister(REG_INPUT, [0x04]);
    checkTrue('pin_read', (await chip.pin(2).read()) === 1);
    await chip.pin(0).write(1);
    checkTrue('pin0_on', connection.registers.get(REG_OUTPUT) === 0x01);
    await chip.pin(2).write(1);
    checkTrue('pin2_on_preserves_pin0', connection.registers.get(REG_OUTPUT) === 0x05);
    await chip.pin(0).write(0);
    checkTrue('pin0_off_preserves_pin2', connection.registers.get(REG_OUTPUT) === 0x04);

    await chip.configurePolarity(0x0F);
    checkTrue('configure_polarity', connection.registers.get(REG_POLARITY) === 0x0F);

    before = connection.writes.length;
    await chip.setDirection(0xF0);
    checkTrue('set_direction_register', connection.registers.get(REG_CONFIG) === 0xF0);
    checkTrue('set_direction_order', eq(dataWrites(connection.writes.slice(before)), [REG_OUTPUT, REG_CONFIG]));
    checkTrue('get_direction', chip.getDirection() === 0xF0);

    checkTrue('read_register', (await chip.readRegister(REG_CONFIG)) === 0xF0);
    await chip.writeRegister(REG_OUTPUT, 0x81);
    checkTrue('write_register_output_shadow', chip._shadow === 0x81);
    await chip.writeRegister(REG_CONFIG, 0x0F);
    checkTrue('write_register_config_shadow', chip._direction === 0x0F);
    let threw = false;
    try { await chip.writeRegister(REG_INPUT, 0); } catch (e) { threw = true; }
    checkTrue('write_register_input_raises', threw);

    // pollInterrupt: changed mask limited to inputs, cache updated.
    await chip.setDirection(0xF0);
    connection.setRegister(REG_INPUT, [0x00]);
    await chip.pollInterrupt();
    connection.setRegister(REG_INPUT, [0x35]);
    checkTrue('poll_interrupt_masks_outputs', (await chip.pollInterrupt()) === 0x30);
    checkTrue('poll_interrupt_no_change', (await chip.pollInterrupt()) === 0x00);

    // watch() on an output pin rejects.
    const chip2 = new Pca6408aFull(new I2CConnectionMock());
    await chip2.ready;
    const out0 = chip2.pin(0, 'out');
    threw = false;
    try { await out0.watch(() => {}); } catch (e) { threw = true; }
    checkTrue('watch_output_raises', threw);

    await chip.reset();
    checkTrue('reset_output', connection.registers.get(REG_OUTPUT) === 0xFF);
    checkTrue('reset_polarity', connection.registers.get(REG_POLARITY) === 0x00);
    checkTrue('reset_config', connection.registers.get(REG_CONFIG) === 0xFF);
    checkTrue('reset_shadows', chip._shadow === 0xFF && chip._direction === 0xFF);

    const m = new Pca6408aMinimal(new I2CConnectionMock());
    await m.ready;
    checkTrue('minimal_i2c', (await m.readPort()) === 0);

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
