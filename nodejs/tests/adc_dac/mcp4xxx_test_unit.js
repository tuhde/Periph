'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { Mcp4xxxFull, Mcp4xxxMinimal, PARTS, defaultAddress } = require('../../packages/periph/src/chips/adc_dac/mcp4xxx');

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

async function throws(fn) {
    try { await fn(); } catch (e) { return true; }
    return false;
}

const hex = (b) => Buffer.from(b).toString('hex');

class Pin {
    constructor(log) { this.log = log; }
    async set(high) { this.log.push(high ? 'HVC1' : 'HVC0'); }
}

/** Models the MCP4XXX command protocol: 16 nine-bit memory cells, inc/dec, two-byte reads. */
class ChipMock extends I2CConnectionMock {
    constructor(maxCode) {
        super();
        this.mem = new Array(16).fill(0);
        this.mem[0] = this.mem[1] = this.mem[2] = this.mem[3] = maxCode / 2;
        this.mem[4] = 0x1FF;
        this.maxCode = maxCode;
        this.status = 0x1F0;
        this.log = [];
    }

    async write(data) {
        const buf = Buffer.from(data);
        this.writes.push(buf);
        this.log.push(buf);
        let i = 0;
        while (i < buf.length) {
            const cmd = buf[i];
            const addr = cmd >> 4;
            const op = (cmd >> 2) & 3;
            if (op === 0) {
                this.mem[addr] = ((cmd & 1) << 8) | buf[i + 1];
                i += 2;
            } else {
                if (addr < 2) {
                    const w = this.mem[addr];
                    this.mem[addr] = op === 1 ? Math.min(this.maxCode, w + 1) : Math.max(0, w - 1);
                }
                i += 1;
            }
        }
    }

    async writeRead(data, n) {
        const buf = Buffer.from(data);
        this.writes.push(buf);
        const addr = buf[0] >> 4;
        const value = addr === 5 ? this.status : this.mem[addr];
        return Buffer.from([(value >> 8) & 1, value & 0xFF]).subarray(0, n);
    }
}

function make(part, ...rest) {
    const conn = new ChipMock(PARTS[part].maxCode);
    return [conn, new Mcp4xxxFull(conn, part, 10000, 75, ...rest)];
}

async function main() {
    checkTrue('sixteen_parts', Object.keys(PARTS).length === 16);
    checkTrue('default_address', defaultAddress('mcp4531') === 0x2E && defaultAddress('MCP4632') === 0x58 && defaultAddress('MCP4661') === 0x28);
    checkTrue('unknown_part_throws', await throws(async () => new Mcp4xxxMinimal(new I2CConnectionMock(), 'MCP9999')));

    // init performs only reads.
    let [conn, chip] = make('MCP4652');
    await chip.init();
    checkTrue('init_reads_only', conn.writes.map(hex).join() === '0c,1c,4c');
    [conn, chip] = make('MCP4662');
    await chip.init();
    checkTrue('init_nv_reads_status', conn.writes.map(hex).join() === '0c,1c,4c,5c');
    checkTrue('props_4662', chip.channels() === 2 && chip.steps() === 257 && !chip.isPotentiometer() && chip.isNonvolatile());

    // set_wiper byte format.
    const [c7, p7] = make('MCP4531');
    checkTrue('props_4531', p7.channels() === 1 && p7.steps() === 129 && p7.isPotentiometer() && !p7.isNonvolatile());
    await p7.setWiper(100);
    checkTrue('set_wiper_7bit', hex(c7.writes.at(-1)) === '0064');
    await p7.setWiper(999);
    checkTrue('set_wiper_clamps_128', hex(c7.writes.at(-1)) === '0080');
    await p7.setWiper(-3);
    checkTrue('set_wiper_clamps_0', hex(c7.writes.at(-1)) === '0000');
    const [c8, p8] = make('MCP4652');
    await p8.setWiper(256, 1);
    checkTrue('set_wiper_8bit_d8', hex(c8.writes.at(-1)) === '1100');
    checkTrue('get_wiper_8bit', (await p8.getWiper(1)) === 256 && hex(c8.writes.at(-1)) === '1c');
    checkTrue('bad_channel_throws', await throws(() => p7.setWiper(1, 1)) && await throws(() => p8.setWiper(1, 2)) && await throws(() => p7.getWiper(1)));

    // Resistance model.
    await p7.setWiper(64);
    checkTrue('get_resistance', Math.abs(await p7.getResistance() - 5075) < 1e-9);
    await p7.setWiper(0);
    checkTrue('get_resistance_zero', Math.abs(await p7.getResistance() - 75) < 1e-9);
    await p7.setResistance(5075);
    checkTrue('set_resistance', (await p7.getWiper()) === 64);
    await p7.setResistance(1e9);
    checkTrue('set_resistance_clamps', (await p7.getWiper()) === 128);
    checkTrue('resistance_wa', Math.abs(await p7.getResistanceWa() - 75) < 1e-9);
    await p7.setResistanceWa(5075);
    checkTrue('set_resistance_wa', (await p7.getWiper()) === 64);
    checkTrue('step', Math.abs(p7.getResistanceStep() - 10000 / 128) < 1e-9);
    const [, rheo] = make('MCP4532');
    checkTrue('rheostat_wa_throws', await throws(() => rheo.getResistanceWa()));

    // Ratio, setBoth.
    await p7.setRatio(0.5);
    checkTrue('set_ratio', (await p7.getWiper()) === 64 && Math.abs(await p7.getRatio() - 0.5) < 1e-9);
    let d;
    [conn, d] = make('MCP4631');
    await d.setBoth(10, 20);
    checkTrue('set_both', conn.mem[0] === 10 && conn.mem[1] === 20);
    checkTrue('set_both_single_throws', await throws(() => p7.setBoth(1, 2)));

    // increment / decrement: one continuous transaction, saturating.
    await d.setWiper(120, 1);
    conn.log.length = 0;
    checkTrue('increment_saturates', (await d.increment(10, 1)) === 128);
    checkTrue('increment_one_write', conn.log.length === 1 && hex(conn.log[0]) === '14'.repeat(10));
    await d.setWiper(5);
    checkTrue('decrement_saturates', (await d.decrement(10)) === 0);
    checkTrue('increment_single', (await d.increment()) === 1 && hex(conn.log.at(-1)) === '04');

    // Shutdown / terminals via TCON.
    [conn, d] = make('MCP4631');
    await d.shutdown(1);
    checkTrue('shutdown_ch1_tcon', conn.mem[4] === (0x1FF & ~0x80) && await d.isShutdown(1) && !(await d.isShutdown(0)));
    await d.resume(1);
    checkTrue('resume_tcon', conn.mem[4] === 0x1FF);
    await d.shutdown(0);
    checkTrue('shutdown_ch0_tcon', conn.mem[4] === (0x1FF & ~0x08));
    await d.resume(0);
    await d.setTerminals(true, false, true, 1);
    const t1 = await d.getTerminals(1);
    const t0 = await d.getTerminals(0);
    checkTrue('terminals_ch1', conn.mem[4] === (0x1FF & ~0x20) && t1.a && !t1.w && t1.b && t0.a && t0.w && t0.b);
    await d.setGeneralCall(false);
    checkTrue('general_call_off', (conn.mem[4] & 0x100) === 0 && (await d.getTcon()) === conn.mem[4]);
    await d.setGeneralCall(true);
    checkTrue('general_call_on', (conn.mem[4] & 0x100) !== 0);

    // reset: volatile -> mid-scale, TCON 0x1FF.
    await d.setWiper(3, 0);
    await d.shutdown(1);
    await d.reset();
    checkTrue('reset_volatile', conn.mem[0] === 64 && conn.mem[1] === 64 && conn.mem[4] === 0x1FF);

    // raw register access.
    checkTrue('read_register', (await d.readRegister(4)) === 0x1FF);
    await d.writeRegister(0, 77);
    checkTrue('write_register', conn.mem[0] === 77);
    checkTrue('bad_register_throws', await throws(() => d.writeRegister(5, 0)) && await throws(() => d.readRegister(2)) && await throws(() => d.readRegister(16)));

    // NV operations throw on a volatile part.
    let all = true;
    for (const fn of [() => d.readStatus(), () => d.waitReady(), () => d.getNvWiper(), () => d.storeWiper(), () => d.readData(0), () => d.setWriteProtect(true)]) {
        all = all && await throws(fn);
    }
    checkTrue('nv_ops_throw_on_volatile', all);

    // --- Non-volatile part ---
    const pins = [];
    let n;
    [conn, n] = make('MCP4662', new Pin(pins));
    checkTrue('status_idle', JSON.stringify(await n.readStatus()) === JSON.stringify({ eepromBusy: false, wiperLock: [false, false], writeProtect: false }));
    conn.status = 0x1F0 | 0x08 | 0x02 | 0x01;
    const st = await n.readStatus();
    checkTrue('status_flags', st.eepromBusy && st.wiperLock[0] && !st.wiperLock[1] && st.writeProtect);
    checkTrue('is_locked', await n.isWiperLocked(0) && !(await n.isWiperLocked(1)) && await n.isWriteProtected());
    checkTrue('wait_ready_timeout', await throws(() => n.waitReady(3)));
    conn.status = 0x1F0;
    await n.waitReady();

    await n.setNvWiper(200, 1);
    checkTrue('set_nv_wiper', conn.mem[3] === 200 && (await n.getNvWiper(1)) === 200);
    await n.setWiper(150, 0);
    checkTrue('store_wiper', (await n.storeWiper(0)) === 150 && conn.mem[2] === 150);
    await n.writeData(3, 0x1AB);
    checkTrue('write_data', conn.mem[9] === 0x1AB && (await n.readData(3)) === 0x1AB);
    checkTrue('write_data_cmd', conn.log.some((b) => hex(b) === '91ab'));
    checkTrue('bad_index_throws', await throws(() => n.readData(10)) && await throws(() => n.writeData(-1, 0)));
    await n.setWiper(7, 0);
    await n.reset();
    checkTrue('nv_reset', conn.mem[0] === 150 && conn.mem[1] === 200);

    // High-voltage commands.
    conn.log.length = 0;
    await n.setWiperLock(true, 0);
    checkTrue('wl_enable_0', hex(conn.log[0]) === '28' && pins.slice(-2).join() === 'HVC1,HVC0');
    await n.setWiperLock(false, 1);
    checkTrue('wl_disable_1', conn.log.some((b) => hex(b) === '34'));
    await n.setWiperLock(true, 1);
    checkTrue('wl_enable_1', conn.log.some((b) => hex(b) === '38'));
    await n.setWriteProtect(true);
    checkTrue('wp_enable', conn.log.some((b) => hex(b) === 'f8'));
    await n.setWriteProtect(false);
    checkTrue('wp_disable', conn.log.some((b) => hex(b) === 'f4'));
    const [, nohv] = make('MCP4562');
    checkTrue('hv_needs_pin', await throws(() => nohv.setWiperLock(true)));

    // setRab: no traffic.
    const before = conn.writes.length;
    n.setRab(50000, 100);
    checkTrue('set_rab_no_traffic', conn.writes.length === before);
    await n.setWiper(128, 0);
    checkTrue('set_rab_resistance', Math.abs(await n.getResistance(0) - 25100) < 1e-9);

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
