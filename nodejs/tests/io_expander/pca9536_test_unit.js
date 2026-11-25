'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { Pca9536Minimal, Pca9536Full } =
    require('../../packages/periph/src/chips/io_expander/pca9536');

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
    const chip = new Pca9536Full(connection);
    await chip.ready;

    checkTrue('init_output', connection.registers.get(REG_OUTPUT) === 0xFF);
    checkTrue('init_polarity', connection.registers.get(REG_POLARITY) === 0x00);
    checkTrue('init_config', connection.registers.get(REG_CONFIG) === 0xFF);
    checkTrue('init_shadows', chip._shadow === 0xFF && chip._direction === 0xFF);
    checkTrue('init_never_writes_input', !dataWrites(connection.writes).includes(REG_INPUT));

    connection.setRegister(REG_INPUT, [0xF5]);
    checkTrue('read_port_masks_unused_bits', (await chip.readPort()) === 0x05);
    checkTrue('read_port_ignores_port', (await chip.readPort(1)) === 0x05);

    await chip.writePort(0, 0x0C);
    checkTrue('write_port_register', connection.registers.get(REG_OUTPUT) === 0xFC);
    checkTrue('write_port_shadow', chip._shadow === 0xFC);
    await chip.writePort(0, 0xFF);
    checkTrue('write_port_masks_high_nibble', connection.registers.get(REG_OUTPUT) === 0xFF);

    const pin3 = chip.pin(3);
    await pin3.setDirection('out');
    checkTrue('p3_output', connection.registers.get(REG_CONFIG) === 0xF7);
    await pin3.setDirection('in');
    checkTrue('p3_input', connection.registers.get(REG_CONFIG) === 0xFF);

    await chip.writePort(0, 0x00);
    let before = connection.writes.length;
    await chip.pin(1).setDirection('out');
    checkTrue('output_before_config', eq(dataWrites(connection.writes.slice(before)), [REG_OUTPUT, REG_CONFIG]));

    let threw = false;
    try { chip.pin(4); } catch (e) { threw = true; }
    checkTrue('pin_range', threw);

    await chip.writePort(0, 0x00);
    connection.setRegister(REG_INPUT, [0x04]);
    checkTrue('pin_read', (await chip.pin(2).read()) === 1);
    await chip.pin(0).write(1);
    checkTrue('pin0_on', connection.registers.get(REG_OUTPUT) === 0xF1);
    await chip.pin(2).write(1);
    checkTrue('pin2_on_preserves_pin0', connection.registers.get(REG_OUTPUT) === 0xF5);
    await chip.pin(0).write(0);
    checkTrue('pin0_off_preserves_pin2', connection.registers.get(REG_OUTPUT) === 0xF4);

    await chip.configurePolarity(0x0F);
    checkTrue('configure_polarity', connection.registers.get(REG_POLARITY) === 0x0F);
    await chip.configurePolarity(0x00);

    before = connection.writes.length;
    await chip.setDirection(0x0C);
    checkTrue('set_direction_register', connection.registers.get(REG_CONFIG) === 0xFC);
    checkTrue('set_direction_order', eq(dataWrites(connection.writes.slice(before)), [REG_OUTPUT, REG_CONFIG]));
    checkTrue('get_direction', chip.getDirection() === 0x0C);

    checkTrue('read_register', (await chip.readRegister(REG_CONFIG)) === 0xFC);
    await chip.writeRegister(REG_OUTPUT, 0xF1);
    checkTrue('write_register_output_shadow', chip._shadow === 0xF1);
    await chip.writeRegister(REG_CONFIG, 0x0F);
    checkTrue('write_register_config_shadow', chip._direction === 0x0F);
    threw = false;
    try { await chip.writeRegister(REG_INPUT, 0); } catch (e) { threw = true; }
    checkTrue('write_register_input_raises', threw);

    // pollChanges: changed mask limited to inputs, cache updated.
    await chip.setDirection(0x0C);
    connection.setRegister(REG_INPUT, [0x00]);
    await chip.pollChanges();
    connection.setRegister(REG_INPUT, [0x0D]);
    checkTrue('poll_changes_masks_outputs', (await chip.pollChanges()) === 0x0C);
    checkTrue('poll_changes_no_change', (await chip.pollChanges()) === 0x00);

    // A polarity change refreshes the cache and is not reported.
    connection.setRegister(REG_INPUT, [0x00]);
    await chip.configurePolarity(0x0C);
    checkTrue('polarity_not_reported', (await chip.pollChanges()) === 0x00);
    await chip.configurePolarity(0x00);
    await chip.pollChanges();

    // watchChanges calls back with the changed mask.
    const seen = [];
    connection.setRegister(REG_INPUT, [0x00]);
    await chip.pollChanges();
    chip.watchChanges((m) => seen.push(m), 0.002);
    connection.setRegister(REG_INPUT, [0x04]);
    await new Promise((r) => setTimeout(r, 100));
    chip.unwatchChanges();
    checkTrue('watch_changes_callback', seen.length === 1 && seen[0] === 0x04);

    // Pin.watch is polling-based and filters by trigger.
    const conn3 = new I2CConnectionMock();
    const chip3 = new Pca9536Full(conn3);
    await chip3.ready;
    const hits = [];
    await chip3.pin(2).watch((p) => hits.push(p._n), 'rising');
    conn3.setRegister(REG_INPUT, [0x04]);
    await new Promise((r) => setTimeout(r, 100));
    conn3.setRegister(REG_INPUT, [0x00]);
    await new Promise((r) => setTimeout(r, 100));
    chip3.unwatchChanges();
    checkTrue('pin_watch_rising_only', hits.length === 1 && hits[0] === 2);

    // watch() on an output pin rejects.
    const chip2 = new Pca9536Full(new I2CConnectionMock());
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

    const m = new Pca9536Minimal(new I2CConnectionMock());
    await m.ready;
    checkTrue('minimal_i2c', (await m.readPort()) === 0);

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
