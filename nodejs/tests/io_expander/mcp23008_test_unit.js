'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { SPIConnectionMock } = require('../../packages/periph/src/connection/spi_mock');
const { Mcp23008Minimal, Mcp23008Full, Mcp23S08Minimal, Mcp23S08Full } =
    require('../../packages/periph/src/chips/io_expander/mcp23008');

const REG_IODIR = 0x00, REG_IPOL = 0x01, REG_GPINTEN = 0x02, REG_DEFVAL = 0x03;
const REG_IOCON = 0x05, REG_GPPU = 0x06, REG_INTF = 0x07, REG_INTCAP = 0x08;
const REG_GPIO = 0x09, REG_OLAT = 0x0A;

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

// The shared SPI mock keys its register lookup on MCP2515/ADXL362 opcodes; the
// MCP23S08 header is [opcode, reg], so resolve reads from the second byte.
class OpcodeSpiMock extends SPIConnectionMock {
    async writeRead(data, n) {
        const buf = Buffer.from(data);
        this.writes.push(buf);
        const out = Buffer.alloc(n);
        for (let i = 0; i < n; i++) out[i] = this.registers.get(buf[1] + i) || 0;
        return out;
    }
}

const last = (w) => Array.from(w[w.length - 1]);
const eq = (a, b) => a.length === b.length && a.every((v, i) => v === b[i]);

async function main() {
    // ---------------------------------------------------------------- I2C
    const connection = new I2CConnectionMock();
    const chip = new Mcp23008Full(connection);
    await chip.ready;

    checkTrue('init_olat', connection.registers.get(REG_OLAT) === 0x00);
    checkTrue('init_shadows', chip._shadow === 0x00 && chip._direction === 0xFF);
    checkTrue('init_no_iodir_write', connection.writes.every((w) => w[0] !== REG_IODIR));

    connection.setRegister(REG_GPIO, [0xA5]);
    checkTrue('read_port', (await chip.readPort()) === 0xA5);
    checkTrue('read_port_ignores_port', (await chip.readPort(1)) === 0xA5);

    await chip.writePort(0, 0x3C);
    checkTrue('write_port_register', connection.registers.get(REG_OLAT) === 0x3C);
    checkTrue('write_port_shadow', chip._shadow === 0x3C);

    // GP7 is a normal bidirectional pin.
    const pin7 = chip.pin(7);
    await pin7.setDirection('out');
    checkTrue('gp7_output', connection.registers.get(REG_IODIR) === 0x7F);
    await pin7.setDirection('in');
    checkTrue('gp7_input', connection.registers.get(REG_IODIR) === 0xFF);

    // OLAT is written before IODIR when a pin becomes an output.
    await chip.writePort(0, 0x00);
    const before = connection.writes.length;
    await chip.pin(1).setDirection('out');
    const seq = connection.writes.slice(before).map((w) => w[0]);
    checkTrue('olat_before_iodir', eq(seq, [REG_OLAT, REG_IODIR]));

    await chip.writePort(0, 0x00);
    connection.setRegister(REG_GPIO, [0x04]);
    checkTrue('pin_read', (await chip.pin(2).read()) === 1);
    await chip.pin(0).write(1);
    checkTrue('pin0_on', connection.registers.get(REG_OLAT) === 0x01);
    await chip.pin(2).write(1);
    checkTrue('pin2_on_preserves_pin0', connection.registers.get(REG_OLAT) === 0x05);
    await chip.pin(0).write(0);
    checkTrue('pin0_off_preserves_pin2', connection.registers.get(REG_OLAT) === 0x04);

    await chip.configurePullup(0xF0);
    checkTrue('configure_pullup', connection.registers.get(REG_GPPU) === 0xF0);
    await chip.configurePolarity(0x0F);
    checkTrue('configure_polarity', connection.registers.get(REG_IPOL) === 0x0F);
    await chip.setDefaultValue(0x11);
    checkTrue('set_default_value', connection.registers.get(REG_DEFVAL) === 0x11);
    await chip.enablePinInterrupt(0xF0);
    checkTrue('enable_pin_interrupt', connection.registers.get(REG_GPINTEN) === 0xF0);

    await chip.configureIntPin({ activeHigh: true });
    checkTrue('iocon_intpol', connection.registers.get(REG_IOCON) === 0x02);
    await chip.configureIntPin({ openDrain: true, activeHigh: true });
    checkTrue('iocon_odr', connection.registers.get(REG_IOCON) === 0x04);
    await chip.configureIntPin();
    checkTrue('iocon_default', connection.registers.get(REG_IOCON) === 0x00);
    await chip.setSequential(false);
    checkTrue('iocon_seqop_off', connection.registers.get(REG_IOCON) === 0x20);
    await chip.setSequential(true);
    checkTrue('iocon_seqop_on', connection.registers.get(REG_IOCON) === 0x00);

    connection.setRegister(REG_INTF, [0x10]);
    connection.setRegister(REG_INTCAP, [0xFF]);
    checkTrue('poll_interrupt', (await chip.pollInterrupt()) === 0x10);
    connection.setRegister(REG_INTCAP, [0x22]);
    checkTrue('read_capture', (await chip.readCapture()) === 0x22);

    await chip.writeRegister(REG_IODIR, 0x0F);
    checkTrue('write_register_iodir_shadow', chip._direction === 0x0F);
    await chip.writeRegister(REG_OLAT, 0x81);
    checkTrue('write_register_olat', chip._shadow === 0x81 && (await chip.readRegister(REG_OLAT)) === 0x81);

    // onInterrupt with no armed pins rejects; watch on an output rejects.
    const chip2 = new Mcp23008Full(new I2CConnectionMock());
    let threw = false;
    try { await chip2.onInterrupt(() => {}); } catch (e) { threw = true; }
    checkTrue('on_interrupt_unarmed_throws', threw);
    await chip2.pin(0).setDirection('out');
    threw = false;
    try { await chip2.pin(0).watch(() => {}); } catch (e) { threw = true; }
    checkTrue('watch_output_throws', threw);

    // watch + polling delivery: INTF/INTCAP drive the handler.
    const wc = new I2CConnectionMock();
    const wchip = new Mcp23008Full(wc);
    await wchip.ready;
    let seen = null;
    await wchip.pin(4).watch((p) => { seen = p; }, 'falling');
    checkTrue('watch_sets_gpinten', wc.registers.get(REG_GPINTEN) === 0x10);
    wc.setRegister(REG_INTF, [0x10]);
    wc.setRegister(REG_INTCAP, [0x00]);
    await wchip.onInterrupt(() => {});
    await new Promise((r) => setTimeout(r, 30));
    await wchip.offInterrupt();
    checkTrue('watch_handler_called', seen !== null);
    await wchip.pin(4).unwatch();
    checkTrue('unwatch_clears_gpinten', wc.registers.get(REG_GPINTEN) === 0x00);

    await chip.reset();
    checkTrue('reset_iodir', connection.registers.get(REG_IODIR) === 0xFF);
    checkTrue('reset_olat', connection.registers.get(REG_OLAT) === 0x00);
    checkTrue('reset_gppu', connection.registers.get(REG_GPPU) === 0x00);
    checkTrue('reset_gpinten', connection.registers.get(REG_GPINTEN) === 0x00);
    checkTrue('reset_shadows', chip._shadow === 0x00 && chip._direction === 0xFF);

    const m = new Mcp23008Minimal(new I2CConnectionMock());
    checkTrue('minimal_i2c', (await m.readPort()) === 0);

    // ---------------------------------------------------------------- SPI
    const spi = new OpcodeSpiMock();
    const schip = new Mcp23S08Full(spi, 2);
    await schip.ready;

    checkTrue('spi_init_haen_addr0', eq(Array.from(spi.writes[0]), [0x40, 0x05, 0x08]));
    checkTrue('spi_init_olat', eq(Array.from(spi.writes[1]), [0x44, 0x0A, 0x00]));

    await schip.writePort(0, 0x5A);
    checkTrue('spi_write_port', eq(last(spi.writes), [0x44, 0x0A, 0x5A]));

    spi.setRegister(REG_GPIO, [0xC3]);
    checkTrue('spi_read_port', (await schip.readPort()) === 0xC3);
    checkTrue('spi_read_header', eq(last(spi.writes), [0x45, 0x09]));

    await schip.configurePullup(0x0F);
    checkTrue('spi_configure_pullup', eq(last(spi.writes), [0x44, 0x06, 0x0F]));
    await schip.configureIntPin({ openDrain: true });
    checkTrue('spi_iocon_keeps_haen', eq(last(spi.writes), [0x44, 0x05, 0x0C]));
    await schip.reset();
    checkTrue('spi_reset_keeps_haen', spi.writes.some((w) => eq(Array.from(w), [0x44, 0x05, 0x08])));

    const sm = new Mcp23S08Minimal(new OpcodeSpiMock(), 0);
    checkTrue('minimal_spi', (await sm.readPort()) === 0);

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
