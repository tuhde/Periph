'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { PCF85063AMinimal, PCF85063AFull } = require('../../packages/periph/src/chips/rtc/pcf85063a');

const _REG_CONTROL_1    = 0x00;
const _REG_CONTROL_2    = 0x01;
const _REG_OFFSET       = 0x02;
const _REG_RAM          = 0x03;
const _REG_SECONDS      = 0x04;
const _REG_SECOND_ALARM = 0x0B;
const _REG_TIMER_VALUE  = 0x10;
const _REG_TIMER_MODE   = 0x11;

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

function checkEq(label, got, expected) {
    if (got === expected) { console.log('PASS', label); passed++; }
    else { console.log(`FAIL ${label}: got ${got}, expected ${expected}`); failed++; }
}

function lastWrite(conn, reg) {
    const w = conn.writes.filter((b) => b.length === 2 && b[0] === reg);
    return w.length ? w[w.length - 1][1] : -1;
}

async function full(conn) {
    const rtc = new PCF85063AFull(conn);
    await rtc.init();
    return rtc;
}

async function main() {
    // init(): clears STOP and 12_24, keeps CIE/CAP_SEL, masks SR; no time write.
    {
        const conn = new I2CConnectionMock();
        conn.setRegister(_REG_CONTROL_1, [0x20 | 0x10 | 0x04 | 0x02 | 0x01]);
        await new PCF85063AMinimal(conn).init();
        checkEq('init_control_1', lastWrite(conn, _REG_CONTROL_1), 0x05);
        checkTrue('init_no_time_write', !conn.writes.some((b) => b.length > 2));
    }

    // getDatetime: 2026-09-23 Wednesday(3) 14:30:45, OS flag masked off.
    {
        const conn = new I2CConnectionMock();
        conn.setRegister(_REG_SECONDS, [0xC5, 0x30, 0x14, 0x23, 0x03, 0x09, 0x26]);
        const rtc = new PCF85063AMinimal(conn);
        await rtc.init();
        const dt = await rtc.getDatetime();
        checkTrue('get_datetime_time', dt.hour === 14 && dt.minute === 30 && dt.second === 45);
        checkTrue('get_datetime_date', dt.year === 2026 && dt.month === 9 && dt.day === 23 && dt.weekday === 3);
    }

    // setDatetime: STOP=1, 7-byte BCD write with OS=0, STOP=0; 12_24 cleared.
    {
        const conn = new I2CConnectionMock();
        const rtc = new PCF85063AMinimal(conn);
        await rtc.init();
        conn.setRegister(_REG_CONTROL_1, [0x02]);
        const before = conn.writes.length;
        await rtc.setDatetime(2026, 9, 23, 3, 14, 30, 45);
        const writes = conn.writes.slice(before);
        const ctrl = writes.filter((b) => b.length === 2 && b[0] === _REG_CONTROL_1).map((b) => b[1]);
        const time = writes.find((b) => b.length === 8 && b[0] === _REG_SECONDS);
        checkTrue('set_datetime_stop_sequence', ctrl.length === 2 && ctrl[0] === 0x20 && ctrl[1] === 0x00);
        checkTrue('set_datetime_bcd_payload',
            time !== undefined && time.slice(1).equals(Buffer.from([0x45, 0x30, 0x14, 0x23, 0x03, 0x09, 0x26])));
        let threw = false;
        try { await rtc.setDatetime(2100, 1, 1, 5, 0, 0, 0); } catch (e) { threw = e instanceof RangeError; }
        checkTrue('set_datetime_rejects_2100', threw);
    }

    // Alarm: null fields disabled; round trip; AF cleared, TF kept.
    {
        const conn = new I2CConnectionMock();
        const rtc = await full(conn);
        conn.setRegister(_REG_CONTROL_2, [0xC8]);
        await rtc.setAlarm({ second: 5, minute: 45, weekday: 6 });
        const regs = [0, 1, 2, 3, 4].map((i) => conn.registers.get(_REG_SECOND_ALARM + i));
        checkTrue('set_alarm_registers', regs.join() === [0x05, 0x45, 0x80, 0x80, 0x06].join());
        const a = await rtc.getAlarm();
        checkTrue('get_alarm_roundtrip', a.second === 5 && a.minute === 45 && a.hour === null && a.day === null && a.weekday === 6);
        const c2 = lastWrite(conn, _REG_CONTROL_2);
        checkTrue('set_alarm_clears_af_keeps_tf', (c2 & 0x48) === 0x08 && (c2 & 0x80) === 0x80);
    }

    // Offset: signed 7-bit round trip.
    {
        const conn = new I2CConnectionMock();
        const rtc = await full(conn);
        await rtc.setOffset(-64, 'course');
        checkEq('set_offset_encoding', conn.registers.get(_REG_OFFSET), 0xC0);
        let o = await rtc.getOffset();
        checkTrue('get_offset_roundtrip', o.offset === -64 && o.mode === 'course');
        await rtc.setOffset(63);
        o = await rtc.getOffset();
        checkTrue('get_offset_positive', o.offset === 63 && o.mode === 'normal');
    }

    // Timer: value, TCF, TI_TP, TE; TIE via enableInterrupt; disable -> TCF=11.
    {
        const conn = new I2CConnectionMock();
        conn.setRegister(_REG_TIMER_MODE, [0x18]);
        const rtc = await full(conn);
        await rtc.configureTimer(30, '1hz', true);
        checkEq('timer_value', conn.registers.get(_REG_TIMER_VALUE), 30);
        checkEq('timer_mode', conn.registers.get(_REG_TIMER_MODE), 0x15);
        await rtc.enableInterrupt(PCF85063AFull.SOURCE_TIMER);
        checkEq('timer_tie', conn.registers.get(_REG_TIMER_MODE), 0x17);
        await rtc.disableTimer();
        checkEq('timer_disabled', conn.registers.get(_REG_TIMER_MODE), 0x19);
        conn.setRegister(_REG_TIMER_VALUE, [17]);
        checkEq('read_timer', await rtc.readTimer(), 17);
    }

    // CLKOUT: COF field, flags kept.
    {
        const conn = new I2CConnectionMock();
        conn.setRegister(_REG_CONTROL_2, [0x48]);
        const rtc = await full(conn);
        await rtc.setClockOutput(1);
        checkEq('clkout_1hz', conn.registers.get(_REG_CONTROL_2), 0x4E);
        await rtc.disableClockOutput();
        checkEq('clkout_disabled', conn.registers.get(_REG_CONTROL_2) & 0x07, 0x07);
        await rtc.setClockOutput(2048);
        checkEq('clkout_2048', conn.registers.get(_REG_CONTROL_2) & 0x07, 0x04);
    }

    // Minute / half-minute interrupt enables.
    {
        const conn = new I2CConnectionMock();
        const rtc = await full(conn);
        await rtc.setMinuteInterrupt('minute');
        checkEq('minute_mi', conn.registers.get(_REG_CONTROL_2) & 0x30, 0x20);
        await rtc.setMinuteInterrupt('half_minute');
        checkEq('half_minute_hmi', conn.registers.get(_REG_CONTROL_2) & 0x30, 0x10);
        await rtc.setMinuteInterrupt('off');
        checkEq('minute_off', conn.registers.get(_REG_CONTROL_2) & 0x30, 0x00);
    }

    // Oscillator load, stop/start, OS handling, RAM.
    {
        const conn = new I2CConnectionMock();
        const rtc = await full(conn);
        await rtc.setOscillatorLoad(12.5);
        checkTrue('load_12_5', (conn.registers.get(_REG_CONTROL_1) & 0x01) !== 0);
        await rtc.setOscillatorLoad(7);
        checkTrue('load_7', (conn.registers.get(_REG_CONTROL_1) & 0x01) === 0);
        await rtc.stopClock();
        checkTrue('stop_clock', (conn.registers.get(_REG_CONTROL_1) & 0x20) !== 0);
        await rtc.startClock();
        checkTrue('start_clock', (conn.registers.get(_REG_CONTROL_1) & 0x20) === 0);
        conn.setRegister(_REG_SECONDS, [0xD9]);
        checkTrue('oscillator_stopped', await rtc.oscillatorStopped());
        await rtc.clearOscillatorStop();
        checkEq('clear_oscillator_stop', conn.registers.get(_REG_SECONDS), 0x59);
        checkTrue('oscillator_ok', !(await rtc.oscillatorStopped()));
        await rtc.writeRam(0xA5);
        checkTrue('ram_roundtrip', conn.registers.get(_REG_RAM) === 0xA5 && (await rtc.readRam()) === 0xA5);
    }

    // pollInterrupt: maps flags and clears only the flags that were set.
    {
        const conn = new I2CConnectionMock();
        const rtc = await full(conn);
        conn.setRegister(_REG_CONTROL_2, [0x80 | 0x40 | 0x20 | 0x05]);
        const status = await rtc.pollInterrupt();
        checkEq('poll_interrupt_status', status, PCF85063AFull.SOURCE_ALARM);
        const c2 = lastWrite(conn, _REG_CONTROL_2);
        checkEq('poll_interrupt_clears_af', c2 & 0x40, 0);
        checkEq('poll_interrupt_keeps_tf', c2 & 0x08, 0x08);
        checkEq('poll_interrupt_keeps_enables', c2 & 0xB7, 0xA5);
        conn.setRegister(_REG_CONTROL_2, [0x08]);
        checkEq('poll_interrupt_timer', await rtc.pollInterrupt(), PCF85063AFull.SOURCE_TIMER);
        conn.setRegister(_REG_CONTROL_2, [0x00]);
        checkEq('poll_interrupt_none', await rtc.pollInterrupt(), 0);
    }

    // enableInterrupt: AIE / MI / HMI in Control_2, CIE in Control_1.
    {
        const conn = new I2CConnectionMock();
        const rtc = await full(conn);
        const S = PCF85063AFull;
        await rtc.enableInterrupt(S.SOURCE_ALARM | S.SOURCE_MINUTE | S.SOURCE_HALF_MINUTE | S.SOURCE_CORRECTION);
        checkEq('enable_interrupt_control_2', conn.registers.get(_REG_CONTROL_2) & 0xB0, 0xB0);
        checkTrue('enable_interrupt_control_1', (conn.registers.get(_REG_CONTROL_1) & 0x04) !== 0);
        await rtc.disableInterrupt(S.SOURCE_ALARM | S.SOURCE_CORRECTION);
        checkEq('disable_interrupt_control_2', conn.registers.get(_REG_CONTROL_2) & 0xB0, 0x30);
        checkTrue('disable_interrupt_control_1', (conn.registers.get(_REG_CONTROL_1) & 0x04) === 0);
    }

    // softwareReset writes 0x58 to Control_1.
    {
        const conn = new I2CConnectionMock();
        const rtc = await full(conn);
        await rtc.softwareReset();
        checkEq('software_reset_sequence', lastWrite(conn, _REG_CONTROL_1), 0x58);
    }

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
