'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { PN532Minimal, PN532Full, PN532Error, _frame } = require('../../packages/periph/src/chips/rfid/pn532');

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

async function rejects(fn) {
    try { await fn(); } catch (e) { return e; }
    return null;
}

const ACK = Buffer.from([0x00, 0x00, 0xFF, 0x00, 0xFF, 0x00]);
const NACK = Buffer.from([0x00, 0x00, 0xFF, 0xFF, 0x00, 0x00]);
const ERROR_FRAME = Buffer.from([0x00, 0x00, 0xFF, 0x01, 0xFF, 0x7F, 0x81, 0x00]);
const B = (a) => Buffer.from(a);

function frame(tfi, data) {
    const body = Buffer.concat([B([tfi]), B(data)]);
    let sum = 0;
    for (const b of body) sum = (sum + b) & 0xFF;
    return Buffer.concat([B([0x00, 0x00, 0xFF, body.length, (0x100 - body.length) & 0xFF]), body, B([(0x100 - sum) & 0xFF, 0x00])]);
}

// Simulates the PN532 I²C frame protocol: ACK, response, NACK resend, ACK abort, status byte.
class Sim extends I2CConnectionMock {
    constructor() {
        super();
        this.queue = [];
        this.last = null;
        this.commands = [];
        this.reads = [];
        this.card = true;
        this.uid = B([0x04, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66]);
        this.noAck = false;
        this.noResponse = false;
        this.errorFrame = false;
        this.badDcs = false;
        this.ic = 0x32;
        this.status = 0;
        this.regs = new Map();
        this.listResponse = null;
    }

    respond(cmd, p) {
        switch (cmd) {
        case 0x02: return B([this.ic, 0x01, 0x06, 0x07]);
        case 0x04: return B([0x00, 0x01, 0x01, 0x01, 0x00, 0x00, 0x10, 0x01]);
        case 0x4A:
            if (this.listResponse) return this.listResponse;
            if (!this.card) return B([0]);
            return Buffer.concat([B([1, 1, 0x00, 0x44, 0x00, this.uid.length]), this.uid]);
        case 0x52: case 0x54: case 0x44: case 0x16: return B([this.status]);
        case 0x40:
            if (this.status) return B([this.status]);
            if (p[1] === 0x30) return B([0, ...Array.from({ length: 16 }, (_, i) => p[2] + i)]);
            return B([0]);
        case 0x42: return Buffer.concat([B([0]), B(Array.from(p).reverse())]);
        case 0x06: return B([this.regs.get((p[0] << 8) | p[1]) || 0]);
        case 0x08: this.regs.set((p[0] << 8) | p[1], p[2]); return B([]);
        case 0x00: return p[0] === 0 ? Buffer.from(p) : B([0]);
        default: return B([]);
        }
    }

    async write(data) {
        const d = Buffer.from(data);
        this.writes.push(d);
        this._maybeFailWrite();
        if (d.equals(ACK)) { this.queue = []; return; }
        if (d.equals(NACK)) { if (this.last) this.queue.push(this.last); return; }
        const ln = d[3];
        const cmd = d[6];
        const params = Buffer.from(d.subarray(7, 5 + ln));
        this.commands.push([cmd, params]);
        if (this.noAck) return;
        this.queue = [ACK];
        if (this.noResponse) return;
        if (this.errorFrame) { this.queue.push(ERROR_FRAME); return; }
        let resp = frame(0xD5, Buffer.concat([B([cmd + 1]), this.respond(cmd, params)]));
        if (this.badDcs) { resp = Buffer.from(resp); resp[resp.length - 2] = (resp[resp.length - 2] + 1) & 0xFF; }
        this.queue.push(resp);
    }

    async read(n) {
        this.reads.push(n);
        const out = Buffer.alloc(n);
        if (this.queue.length === 0) return out;
        out[0] = 0x01;
        if (n === 1) return out;
        const item = this.queue.shift();
        if (!item.equals(ACK)) this.last = item;
        for (let i = 1; i < n; i++) out[i] = i - 1 < item.length ? item[i - 1] : 0xFF;
        return out;
    }
}

const cmdIs = (c, cmd, params) => c[0] === cmd && c[1].equals(B(params));
const last = (sim) => sim.commands[sim.commands.length - 1];

(async () => {
    checkTrue('frame_get_firmware_version', _frame(0x02, []).equals(B([0x00, 0x00, 0xFF, 0x02, 0xFE, 0xD4, 0x02, 0x2A, 0x00])));

    let sim = new Sim();
    let m = new PN532Minimal(sim);
    await m.init();
    checkTrue('init_commands', sim.commands.map((c) => c[0]).join() === [0x02, 0x14, 0x32].join());
    checkTrue('init_sam', cmdIs(sim.commands[1], 0x14, [0x01, 0x14, 0x01]));
    checkTrue('init_retries', cmdIs(sim.commands[2], 0x32, [0x05, 0xFF, 0x01, 0x02]));
    checkTrue('init_nack_after_header', sim.writes[1].equals(NACK));
    checkTrue('ack_read_7', sim.reads.filter((r) => r === 7).length === 3);
    checkTrue('response_full_read_len', sim.reads.includes(14));

    let uid = await m.readUid();
    checkTrue('read_uid_value', uid && uid.equals(sim.uid));
    checkTrue('read_uid_list_params', cmdIs(sim.commands[3], 0x4A, [0x01, 0x00]));
    checkTrue('read_uid_release', cmdIs(sim.commands[4], 0x52, [0x01]));
    sim.card = false;
    checkTrue('read_uid_no_card', (await m.readUid()) === null);
    sim.noResponse = true;
    checkTrue('read_uid_timeout_none', (await m.readUid(0.01)) === null);
    checkTrue('read_uid_timeout_abort', sim.writes[sim.writes.length - 1].equals(ACK));
    sim.noResponse = false;
    sim.errorFrame = true;
    checkTrue('error_frame_raises', (await rejects(() => m.readUid())) instanceof PN532Error);
    sim.errorFrame = false;
    sim.badDcs = true;
    checkTrue('bad_dcs_raises', (await rejects(() => m.readUid())) instanceof PN532Error);

    sim = new Sim();
    sim.ic = 0x31;
    checkTrue('init_bad_ic_raises', (await rejects(() => new PN532Minimal(sim).init())) instanceof PN532Error);
    sim = new Sim();
    sim.failNextWrites(3);
    await new PN532Minimal(sim).init();
    checkTrue('wake_write_retry', sim.commands.length === 3);
    sim = new Sim();
    sim.noAck = true;
    checkTrue('no_ack_raises', (await rejects(() => new PN532Minimal(sim).init())) instanceof PN532Error);
    checkTrue('no_ack_three_attempts', sim.commands.length === 3);

    sim = new Sim();
    const f = new PN532Full(sim);
    await f.init();
    const fw = await f.firmwareVersion();
    checkTrue('fw_ic', fw.ic === 0x32 && fw.version === 1 && fw.revision === 6);
    checkTrue('fw_support', fw.support === 7 && fw.iso14443a && fw.iso14443b && fw.iso18092);
    const gs = await f.generalStatus();
    checkTrue('general_status', gs.error === 0 && gs.field && gs.targets === 1 && gs.samStatus === 0x01);

    sim.listResponse = B([2, 1, 0x00, 0x04, 0x20, 4, 1, 2, 3, 4, 5, 0x75, 0x77, 0x81, 0x02,
        2, 0x00, 0x44, 0x00, 7, 9, 9, 9, 9, 9, 9, 9]);
    let t = await f.listPassiveTargets({ maxTargets: 2 });
    checkTrue('list_two_targets', t.length === 2);
    checkTrue('list_target_a', t[0].tg === 1 && t[0].sensRes === 0x0004 && t[0].selRes === 0x20 && t[0].uid.equals(B([1, 2, 3, 4])));
    checkTrue('list_target_ats', t[0].ats.equals(B([5, 0x75, 0x77, 0x81, 0x02])));
    checkTrue('list_target_b', t[1].tg === 2 && t[1].sensRes === 0x0044 && t[1].uid.equals(B(new Array(7).fill(9))) && t[1].ats.length === 0);
    checkTrue('list_params', cmdIs(last(sim), 0x4A, [0x02, 0x00]));
    sim.listResponse = B([1, 1, 20, 0x01, 1, 2, 3, 4, 5, 6, 7, 8, 0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x12, 0xFC]);
    t = await f.listPassiveTargets({ baud: PN532Full.BAUD_212_F, initiatorData: [0x00, 0xFF, 0xFF, 0x01, 0x00] });
    checkTrue('list_felica', t.length === 1 && t[0].uid.equals(B([1, 2, 3, 4, 5, 6, 7, 8])) &&
        t[0].ats.equals(B([0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x12, 0xFC])));
    checkTrue('list_felica_params', cmdIs(last(sim), 0x4A, [0x01, 0x01, 0x00, 0xFF, 0xFF, 0x01, 0x00]));
    sim.listResponse = B([1, 1, 0x50, 0xA1, 0xA2, 0xA3, 0xA4, 0, 0, 0, 0, 0x81, 0x81, 0x00, 1, 0x00]);
    t = await f.listPassiveTargets({ baud: PN532Full.BAUD_106_B, initiatorData: [0x00] });
    checkTrue('list_type_b', t.length === 1 && t[0].uid.equals(B([0xA1, 0xA2, 0xA3, 0xA4])) && t[0].ats.equals(B([0x00])));
    sim.listResponse = B([0]);
    checkTrue('list_empty', (await f.listPassiveTargets()).length === 0);
    sim.listResponse = null;

    checkTrue('mifare_read_block', (await f.mifareReadBlock(1, 4)).equals(B(Array.from({ length: 16 }, (_, i) => 4 + i))));
    checkTrue('mifare_read_cmd', cmdIs(last(sim), 0x40, [0x01, 0x30, 0x04]));
    await f.mifareAuthenticate(1, 7, new Array(6).fill(0xFF), sim.uid);
    checkTrue('mifare_auth_payload', cmdIs(last(sim), 0x40, [0x01, 0x60, 0x07, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x33, 0x44, 0x55, 0x66]));
    await f.mifareAuthenticate(1, 7, new Array(6).fill(1), [0xDE, 0xAD, 0xBE, 0xEF], true);
    checkTrue('mifare_auth_key_b', last(sim)[1][1] === 0x61);
    await f.mifareWriteBlock(1, 4, new Array(16).fill(0));
    checkTrue('mifare_write_cmd', cmdIs(last(sim), 0x40, [0x01, 0xA0, 0x04, ...new Array(16).fill(0)]));
    checkTrue('ntag_read_page', (await f.ntagReadPage(1, 4))[0] === 4);
    await f.ntagWritePage(1, 5, [1, 2, 3, 4]);
    checkTrue('ntag_write_cmd', cmdIs(last(sim), 0x40, [0x01, 0xA2, 0x05, 1, 2, 3, 4]));
    checkTrue('ntag_write_len_check', (await rejects(() => f.ntagWritePage(1, 5, [1]))) instanceof RangeError);
    checkTrue('communicate_thru', (await f.communicateThru([1, 2, 3])).equals(B([3, 2, 1])));

    sim.status = 0x14;
    let e = await rejects(() => f.dataExchange(1, [0x30, 0x00]));
    checkTrue('data_exchange_status_raises', e instanceof PN532Error && e.code === 0x14);
    e = await rejects(() => f.release(1));
    checkTrue('release_status_raises', e instanceof PN532Error && e.code === 0x14);
    sim.status = 0;
    await f.release();
    checkTrue('release_all', cmdIs(last(sim), 0x52, [0x00]));
    await f.select(1);
    checkTrue('select', cmdIs(last(sim), 0x54, [0x01]));
    await f.deselect(1);
    checkTrue('deselect', cmdIs(last(sim), 0x44, [0x01]));

    await f.setRfField(false);
    checkTrue('rf_field_off', cmdIs(last(sim), 0x32, [0x01, 0x00]));
    await f.setRfField(true, true);
    checkTrue('rf_field_on_rfca', cmdIs(last(sim), 0x32, [0x01, 0x03]));
    await f.setMaxRetries({ passiveActivation: 0xFF });
    checkTrue('max_retries', cmdIs(last(sim), 0x32, [0x05, 0xFF, 0x01, 0xFF]));
    await f.writeRegister(0x6302, 0x5A);
    checkTrue('write_register', cmdIs(last(sim), 0x08, [0x63, 0x02, 0x5A]));
    checkTrue('read_register', (await f.readRegister(0x6302)) === 0x5A);
    checkTrue('read_register_cmd', cmdIs(last(sim), 0x06, [0x63, 0x02]));
    checkTrue('self_test_echo', await f.selfTest());
    checkTrue('self_test_echo_cmd', cmdIs(last(sim), 0x00, [0x00, ...Buffer.from('PERIPH01')]));
    checkTrue('self_test_rom', await f.selfTest(0x01));
    checkTrue('send_command', (await f.sendCommand(0x02)).equals(B([0x32, 0x01, 0x06, 0x07])));
    await f.powerDown();
    checkTrue('power_down', cmdIs(last(sim), 0x16, [0x20]));

    await f.startCommand(0x02);
    checkTrue('wait_ready_pending', await f.waitReady(0.01));
    checkTrue('poll_interrupt_ready', await f.pollInterrupt());
    checkTrue('read_response', (await f.readResponse(0x02)).equals(B([0x32, 0x01, 0x06, 0x07])));
    checkTrue('poll_interrupt_idle', !(await f.pollInterrupt()));
    checkTrue('wait_ready_idle', !(await f.waitReady(0.005)));

    const pin = { handler: null, async onEdge(h) { this.handler = h; }, async offEdge(h) { if (this.handler === h) this.handler = null; } };
    let hits = 0;
    await f.onInterrupt(() => { hits++; }, pin);
    checkTrue('on_interrupt_registers', pin.handler !== null);
    pin.handler();
    checkTrue('on_interrupt_callback', hits === 1);
    await f.offInterrupt();
    checkTrue('off_interrupt', pin.handler === null);

    hits = 0;
    await f.onInterrupt(() => { hits++; });
    await f.startCommand(0x02);
    await new Promise((r) => setTimeout(r, 50));
    await f.offInterrupt();
    checkTrue('on_interrupt_polling', hits === 1);

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
})();
