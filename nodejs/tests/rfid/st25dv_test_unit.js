'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { ST25DVMinimal, ST25DVFull, ST25DVSource } = require('../../packages/periph/src/chips/rfid/st25dv');

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

function eq(a, b) { return Buffer.compare(Buffer.from(a), Buffer.from(b)) === 0; }

async function rejects(fn, type = RangeError) {
    try { await fn(); } catch (e) { return e instanceof type; }
    return false;
}

function reg(c, a) { return c.registers.get(a) || 0; }

function make(icRef = 0x26, mem = [0xFF, 0x01]) {
    const user = new I2CConnectionMock();
    const system = new I2CConnectionMock();
    user.setAddressWidth(2);
    system.setAddressWidth(2);
    system.setRegister(0x0014, [mem[0], mem[1], 0x03, icRef]);
    system.setRegister(0x0018, [0x11, 0x22, 0x33, 0x44, 0x55, 0x26, 0x02, 0xE0]);
    system.setRegister(0x0020, [0x12]);
    system.setRegister(0x0000, [0x88]);
    system.setRegister(0x0002, [0x01]);
    return { user, system };
}

async function main() {
    // --- Minimal ---
    let { user, system } = make();
    const min = new ST25DVMinimal(user, system);
    checkTrue('ctor_no_traffic', user.writes.length === 0 && system.writes.length === 0);
    await min.init();
    checkTrue('init_reads_ident', system.writes.length === 1 && eq(system.writes[0], [0x00, 0x14]));
    checkTrue('init_no_user_traffic', user.writes.length === 0);
    checkTrue('size_16k', min._size === 2048);

    user.setRegister(0x0100, [1, 2, 3]);
    checkTrue('read', eq(await min.read(0x0100, 3), [1, 2, 3]));
    checkTrue('read_address_bytes', eq(user.writes[user.writes.length - 1], [0x01, 0x00]));
    checkTrue('read_out_of_range', await rejects(() => min.read(2047, 2)));
    checkTrue('read_negative', await rejects(() => min.read(-1, 1)));

    user.writes.length = 0;
    const data = Buffer.from(Array.from({ length: 40 }, (_, i) => i));
    await min.write(0x0008, data);
    const frames = user.writes.filter((w) => w.length > 2);
    const polls = user.writes.filter((w) => w.length === 2);
    checkTrue('write_first_chunk_to_16_boundary', eq(frames[0], Buffer.concat([Buffer.from([0x00, 0x08]), data.subarray(0, 8)])));
    checkTrue('write_second_chunk', eq(frames[1], Buffer.concat([Buffer.from([0x00, 0x10]), data.subarray(8, 24)])));
    checkTrue('write_third_chunk', eq(frames[2], Buffer.concat([Buffer.from([0x00, 0x20]), data.subarray(24, 40)])));
    checkTrue('write_chunk_count', frames.length === 3);
    checkTrue('write_ack_polled_each_chunk', polls.length === 3);
    let same = true;
    for (let i = 0; i < 40; i++) same = same && reg(user, 0x08 + i) === i;
    checkTrue('write_applied', same);
    checkTrue('write_out_of_range', await rejects(() => min.write(2040, Buffer.alloc(9))));
    user.failNextWrites(1000, 1);
    checkTrue('write_timeout_rejects', await rejects(() => min.write(0, [1]), Error));
    user.failNextWrites(0);

    let t = make(0x24, [0x7F, 0x00]);
    let f = new ST25DVFull(t.user, t.system);
    await f.init();
    checkTrue('size_04k', f.memorySize() === 512);
    await f.writeNdef([1]);
    checkTrue('ndef_cc_04k', eq([0, 1, 2, 3].map((i) => reg(t.user, i)), [0xE1, 0x40, 0x40, 0x01]));
    t = make(0x26, [0xFF, 0x07]);
    f = new ST25DVFull(t.user, t.system);
    await f.init();
    checkTrue('size_64k', f.memorySize() === 8192);
    await f.writeNdef([1, 2]);
    checkTrue('ndef_cc_64k', eq([0, 1, 2, 3, 4, 5, 6, 7].map((i) => reg(t.user, i)), [0xE2, 0x40, 0x00, 0x01, 0x00, 0x00, 0x03, 0xFF]));
    checkTrue('read_ndef_64k', eq(await f.readNdef(), [1, 2]));

    // --- Full: identification ---
    ({ user, system } = make());
    const tag = new ST25DVFull(user, system);
    await tag.init();
    checkTrue('memory_size', tag.memorySize() === 2048);
    checkTrue('ic_reference', tag.icReference() === 0x26);
    checkTrue('uid_msb_first', eq(await tag.uid(), [0xE0, 0x02, 0x26, 0x55, 0x44, 0x33, 0x22, 0x11]));
    checkTrue('ic_revision', (await tag.icRevision()) === 0x12);
    checkTrue('read_system', eq(await tag.readSystem(0x0017, 1), [0x26]));
    system.writes.length = 0;
    await tag.writeSystem(0x0001, 5);
    checkTrue('write_system_frame', eq(system.writes[0], [0x00, 0x01, 0x05]));
    checkTrue('write_system_ack_poll', eq(system.writes[1], [0x00, 0x00]));

    // --- password ---
    const pw = Buffer.from([1, 2, 3, 4, 5, 6, 7, 8]);
    user.setRegister(0x2004, [0x01]);
    system.writes.length = 0;
    checkTrue('present_password_open', await tag.presentPassword(pw));
    checkTrue('present_password_frame', eq(system.writes[0], Buffer.concat([Buffer.from([0x09, 0x00]), pw, Buffer.from([0x09]), pw])));
    checkTrue('session_open', await tag.sessionOpen());
    user.setRegister(0x2004, [0x00]);
    checkTrue('present_password_closed', !(await tag.presentPassword(pw)));
    checkTrue('password_length', await rejects(() => tag.presentPassword([0])));
    checkTrue('write_password_closed_rejects', await rejects(() => tag.writePassword(pw), Error));
    user.setRegister(0x2004, [0x01]);
    system.writes.length = 0;
    await tag.writePassword(pw);
    checkTrue('write_password_frame', eq(system.writes[0], Buffer.concat([Buffer.from([0x09, 0x00]), pw, Buffer.from([0x07]), pw])));

    // --- areas ---
    system.writes.length = 0;
    await tag.setAreaEnd(2, 0x1F);
    checkTrue('set_area_end', eq(system.writes[0], [0x00, 0x07, 0x1F]));
    checkTrue('set_area_end_range', await rejects(() => tag.setAreaEnd(4, 1)));
    system.setRegister(0x000B, [0x00]);
    await tag.setAreaProtection(3, 0x05, 0x03);
    checkTrue('area_protection_rf', reg(system, 0x0008) === 0x05);
    checkTrue('area_protection_i2c', reg(system, 0x000B) === 0x30);
    await tag.setAreaProtection(2, 0x0C, 0x01);
    checkTrue('area_protection_i2c_rmw', reg(system, 0x000B) === 0x34);
    let p = await tag.getAreaProtection(3);
    checkTrue('get_area_protection', p.rf === 0x05 && p.i2c === 0x03);
    p = await tag.getAreaProtection(2);
    checkTrue('get_area_protection_2', p.rf === 0x0C && p.i2c === 0x01);
    checkTrue('area_protection_range', await rejects(() => tag.setAreaProtection(5, 0, 0)));
    checkTrue('area_protection_level', await rejects(() => tag.setAreaProtection(1, 0, 4)));

    // --- GPO ---
    await tag.setGpo(ST25DVFull.GPO_FIELD_CHANGE | ST25DVFull.GPO_RF_WRITE);
    checkTrue('set_gpo', reg(system, 0x0000) === 0xC8);
    await tag.setGpo(ST25DVFull.GPO_FIELD_CHANGE, false);
    checkTrue('set_gpo_disabled', reg(system, 0x0000) === 0x08);
    checkTrue('get_gpo', (await tag.getGpo()) === 0x08);
    await tag.setGpoRuntime(true);
    checkTrue('set_gpo_runtime', reg(user, 0x2000) === 0x80);
    await tag.setGpoRuntime(false);
    checkTrue('set_gpo_runtime_off', reg(user, 0x2000) === 0x00);
    await tag.setInterruptPulse(3);
    checkTrue('set_interrupt_pulse', reg(system, 0x0001) === 3);
    checkTrue('interrupt_pulse_range', await rejects(() => tag.setInterruptPulse(8)));

    // --- EH, RF ---
    user.setRegister(0x2002, [0x0C]);
    checkTrue('field_present', await tag.fieldPresent());
    user.setRegister(0x2002, [0x08]);
    checkTrue('field_absent', !(await tag.fieldPresent()));
    system.setRegister(0x0002, [0x01]);
    system.writes.length = 0;
    await tag.setEnergyHarvesting(true);
    checkTrue('eh_no_static_write', system.writes.length === 1 && eq(system.writes[0], [0x00, 0x02]));
    checkTrue('eh_dynamic', reg(user, 0x2002) === 0x01);
    await tag.setEnergyHarvesting(false, true);
    checkTrue('eh_forced_static', reg(system, 0x0002) === 0x00);
    checkTrue('eh_dynamic_off', reg(user, 0x2002) === 0x00);
    await tag.setRfState(false, true);
    checkTrue('rf_sleep', reg(user, 0x2003) === 0x02);
    await tag.setRfState(true);
    checkTrue('rf_disable', reg(user, 0x2003) === 0x01);
    await tag.setRfState();
    checkTrue('rf_normal', reg(user, 0x2003) === 0x00);

    // --- mailbox ---
    system.setRegister(0x000D, [0x00]);
    await tag.enableMailbox();
    checkTrue('mb_mode_static', reg(system, 0x000D) === 0x01);
    checkTrue('mb_en', reg(user, 0x2006) === 0x01);
    system.writes.length = 0;
    await tag.enableMailbox();
    checkTrue('mb_mode_not_rewritten', system.writes.every((w) => w.length === 2));
    user.setRegister(0x2006, [0x01 | 0x04 | 0x20]);
    const st = await tag.mailboxStatus();
    checkTrue('mb_status_enabled', st.enabled);
    checkTrue('mb_status_rf_put', st.rfPut && !st.hostPut);
    checkTrue('mb_status_rf_miss', st.rfMiss && !st.hostMiss);
    checkTrue('mb_write_busy', await rejects(() => tag.mailboxWrite([0x78]), Error));
    user.setRegister(0x2006, [0x01]);
    user.writes.length = 0;
    const msg = Buffer.from(Array.from({ length: 100 }, (_, i) => i));
    await tag.mailboxWrite(msg);
    checkTrue('mb_write_single_frame', eq(user.writes[user.writes.length - 1], Buffer.concat([Buffer.from([0x20, 0x08]), msg])));
    checkTrue('mb_write_len', await rejects(() => tag.mailboxWrite([])));
    checkTrue('mb_write_len_max', await rejects(() => tag.mailboxWrite(Buffer.alloc(257))));
    user.setRegister(0x2006, [0x00]);
    checkTrue('mb_write_disabled', await rejects(() => tag.mailboxWrite([0x78]), Error));
    checkTrue('mb_read_empty', (await tag.mailboxRead()).length === 0);
    user.setRegister(0x2006, [0x05]);
    user.setRegister(0x2007, [19]);
    user.setRegister(0x2008, Array.from({ length: 20 }, (_, i) => 0xA0 + i));
    checkTrue('mb_read', eq(await tag.mailboxRead(), Array.from({ length: 20 }, (_, i) => 0xA0 + i)));
    await tag.disableMailbox();
    checkTrue('mb_disable', reg(user, 0x2006) === 0x00);
    await tag.setMailboxWatchdog(4);
    checkTrue('mb_wdg', reg(system, 0x000E) === 4);
    checkTrue('mb_wdg_range', await rejects(() => tag.setMailboxWatchdog(8)));

    // --- CC lock, NDEF ---
    await tag.lockCcfile();
    checkTrue('lock_ccfile', reg(system, 0x000C) === 0x03);
    await tag.lockCcfile(false);
    checkTrue('unlock_ccfile', reg(system, 0x000C) === 0x00);
    const ndef = [0xD1, 0x01, 0x04, 0x54, 0x02, 0x65, 0x6E, 0x48];
    await tag.writeNdef(ndef);
    checkTrue('ndef_cc_16k', eq([0, 1, 2, 3].map((i) => reg(user, i)), [0xE1, 0x40, 0xFF, 0x01]));
    checkTrue('ndef_tlv', reg(user, 4) === 0x03 && reg(user, 5) === ndef.length);
    checkTrue('ndef_terminator', reg(user, 6 + ndef.length) === 0xFE);
    checkTrue('read_ndef', eq(await tag.readNdef(), ndef));
    const longMsg = Buffer.alloc(300);
    await tag.writeNdef(longMsg);
    checkTrue('ndef_long_tlv', eq([4, 5, 6, 7].map((i) => reg(user, i)), [0x03, 0xFF, 0x01, 0x2C]));
    checkTrue('read_ndef_long', eq(await tag.readNdef(), longMsg));
    checkTrue('ndef_too_large', await rejects(() => tag.writeNdef(Buffer.alloc(2048))));
    user.setRegister(0x0000, [0x00]);
    checkTrue('read_ndef_no_cc', (await tag.readNdef()).length === 0);

    user.failNextWrites(2);
    await tag.waitWriteComplete();
    checkTrue('wait_write_complete_after_nacks', true);
    user.failNextWrites(1000);
    checkTrue('wait_write_complete_timeout', await rejects(() => tag.waitWriteComplete(), Error));
    user.failNextWrites(0);

    // --- interrupts ---
    system.setRegister(0x0000, [0x80]);
    await tag.enableInterrupt(ST25DVSource.FIELD_RISING | ST25DVSource.RF_WRITE);
    checkTrue('enable_interrupt_gpo', reg(system, 0x0000) === 0xC8);
    checkTrue('enable_interrupt_gpo_en', reg(user, 0x2000) === 0x80);
    await tag.enableInterrupt(ST25DVSource.RF_PUT_MSG | ST25DVSource.RF_USER);
    checkTrue('enable_interrupt_mapping', reg(system, 0x0000) === 0xD9);
    await tag.disableInterrupt(ST25DVSource.RF_WRITE);
    checkTrue('disable_interrupt', reg(system, 0x0000) === 0x99);
    user.setRegister(0x2005, [0x90]);
    checkTrue('poll_interrupt', (await tag.pollInterrupt()) === 0x90);

    const pin = {
        handler: null, trigger: null,
        async onEdge(h, tr) { this.handler = h; this.trigger = tr; },
        async offEdge(h) { if (h === this.handler) this.handler = null; },
    };
    const got = [];
    await tag.onInterrupt((s) => got.push(s), pin);
    checkTrue('on_interrupt_falling', pin.trigger === 'falling');
    user.setRegister(0x2005, [0x10]);
    await tag._service();
    checkTrue('on_interrupt_dispatch', got.length === 1 && got[0] === 0x10);
    user.setRegister(0x2005, [0x00]);
    await tag._service();
    checkTrue('on_interrupt_no_spurious', got.length === 1);
    await tag.offInterrupt();
    checkTrue('off_interrupt', pin.handler === null);
    const th = make();
    const tagH = new ST25DVFull(th.user, th.system, true);
    const pinH = { trigger: null, async onEdge(h, tr) { this.trigger = tr; }, async offEdge() {} };
    await tagH.onInterrupt(() => {}, pinH);
    checkTrue('on_interrupt_rising_jf', pinH.trigger === 'rising');
    await tagH.offInterrupt();
    await tag.onInterrupt(() => {});
    checkTrue('on_interrupt_polling_fallback', tag._pollTimer !== null);
    await tag.offInterrupt();
    checkTrue('off_interrupt_stops_polling', tag._pollTimer === null);

    // --- read retry ---
    user.failNextWrites(2);
    user.setRegister(0x0040, [0x77]);
    checkTrue('read_retries_after_nacks', eq(await tag.read(0x0040, 1), [0x77]));
    user.failNextWrites(3);
    checkTrue('read_rejects_after_retries', await rejects(() => tag.read(0x0040, 1), Error));
    user.failNextWrites(0);

    console.log('===DONE: ' + passed + ' passed, ' + failed + ' failed===');
    process.exit(failed === 0 ? 0 : 1);
}

main().catch(e => { console.error(e); process.exit(1); });
