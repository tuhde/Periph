'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { PCF8523Minimal, PCF8523Full } = require('../../packages/periph/src/chips/rtc/pcf8523');

const _REG_CONTROL_1       = 0x00;
const _REG_CONTROL_2       = 0x01;
const _REG_CONTROL_3       = 0x02;
const _REG_SECONDS         = 0x03;
const _REG_MINUTE_ALARM    = 0x0A;
const _REG_OFFSET          = 0x0E;
const _REG_TMR_CLKOUT_CTRL = 0x0F;
const _REG_TMR_A_FREQ_CTRL = 0x10;
const _REG_TMR_A_REG       = 0x11;
const _REG_TMR_B_FREQ_CTRL = 0x12;

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
    const rtc = new PCF8523Full(conn);
    await rtc.init();
    return rtc;
}

async function main() {
    // init(): presence read + CONTROL_3 = 0x00 (PM=000).
    {
        const conn = new I2CConnectionMock();
        conn.setRegister(_REG_CONTROL_3, [0xE0]);
        await new PCF8523Minimal(conn).init();
        checkEq('init_sets_pm_standard', lastWrite(conn, _REG_CONTROL_3), 0x00);
    }

    // getDatetime: 2026-09-23 Wednesday(3) 14:30:45, OS flag masked off.
    {
        const conn = new I2CConnectionMock();
        conn.setRegister(_REG_SECONDS, [0xC5, 0x30, 0x14, 0x23, 0x03, 0x09, 0x26]);
        const rtc = new PCF8523Minimal(conn);
        await rtc.init();
        const dt = await rtc.getDatetime();
        checkTrue('get_datetime_time', dt.hour === 14 && dt.minute === 30 && dt.second === 45);
        checkTrue('get_datetime_date', dt.year === 2026 && dt.month === 9 && dt.day === 23 && dt.weekday === 3);
    }

    // setDatetime: STOP=1, 7-byte BCD write with OS=0, STOP=0; 12_24 cleared.
    {
        const conn = new I2CConnectionMock();
        conn.setRegister(_REG_CONTROL_1, [0x08]);
        const rtc = new PCF8523Minimal(conn);
        await rtc.init();
        const before = conn.writes.length;
        await rtc.setDatetime(2026, 9, 23, 3, 14, 30, 45);
        const writes = conn.writes.slice(before);
        const ctrl = writes.filter((b) => b.length === 2 && b[0] === _REG_CONTROL_1).map((b) => b[1]);
        const time = writes.find((b) => b.length === 8 && b[0] === _REG_SECONDS);
        checkTrue('set_datetime_stop_sequence', ctrl.length === 2 && ctrl[0] === 0x20 && ctrl[1] === 0x00);
        checkTrue('set_datetime_bcd_payload',
            time !== undefined && time.slice(1).equals(Buffer.from([0x45, 0x30, 0x14, 0x23, 0x03, 0x09, 0x26])));
    }

    // Alarm: null fields disabled; round trip.
    {
        const conn = new I2CConnectionMock();
        const rtc = await full(conn);
        await rtc.setAlarm({ minute: 45, weekday: 6 });
        const regs = [0, 1, 2, 3].map((i) => conn.registers.get(_REG_MINUTE_ALARM + i));
        checkTrue('set_alarm_registers', regs.join() === [0x45, 0x80, 0x80, 0x06].join());
        const a = await rtc.getAlarm();
        checkTrue('get_alarm_roundtrip', a.minute === 45 && a.hour === null && a.day === null && a.weekday === 6);
    }

    // Offset: signed 7-bit round trip.
    {
        const conn = new I2CConnectionMock();
        const rtc = await full(conn);
        await rtc.setOffset(-64, 'every_minute');
        checkEq('set_offset_encoding', conn.registers.get(_REG_OFFSET), 0xC0);
        let o = await rtc.getOffset();
        checkTrue('get_offset_roundtrip', o.offset === -64 && o.mode === 'every_minute');
        await rtc.setOffset(63);
        o = await rtc.getOffset();
        checkTrue('get_offset_positive', o.offset === 63 && o.mode === 'every_two_hours');
    }

    // Timer A watchdog: TAQ, T_A, TAC=10, TAM; enableInterrupt picks WTAIE.
    {
        const conn = new I2CConnectionMock();
        conn.setRegister(_REG_TMR_CLKOUT_CTRL, [0x38]);
        const rtc = await full(conn);
        await rtc.configureTimerA('watchdog', 5, '1hz', true);
        checkEq('timer_a_freq', conn.registers.get(_REG_TMR_A_FREQ_CTRL), 0x02);
        checkEq('timer_a_value', conn.registers.get(_REG_TMR_A_REG), 5);
        checkEq('timer_a_ctrl', conn.registers.get(_REG_TMR_CLKOUT_CTRL), 0xBC);
        await rtc.enableInterrupt(PCF8523Full.SOURCE_TIMER_A);
        checkEq('timer_a_watchdog_ie', lastWrite(conn, _REG_CONTROL_2) & 0x07, 0x04);
        await rtc.disableTimerA();
        checkEq('timer_a_disabled', conn.registers.get(_REG_TMR_CLKOUT_CTRL) & 0x06, 0);
    }

    // Timer B: nearest TBW (130 ms -> 125 ms), TBQ, TBC.
    {
        const conn = new I2CConnectionMock();
        conn.setRegister(_REG_TMR_CLKOUT_CTRL, [0x38]);
        const rtc = await full(conn);
        await rtc.configureTimerB(30, '1_60hz', 130);
        checkEq('timer_b_freq', conn.registers.get(_REG_TMR_B_FREQ_CTRL), 0x43);
        checkEq('timer_b_enabled', conn.registers.get(_REG_TMR_CLKOUT_CTRL), 0x39);
    }

    // CLKOUT.
    {
        const conn = new I2CConnectionMock();
        conn.setRegister(_REG_TMR_CLKOUT_CTRL, [0x00]);
        const rtc = await full(conn);
        await rtc.setClockOutput(1);
        checkEq('clkout_1hz', conn.registers.get(_REG_TMR_CLKOUT_CTRL), 0x30);
        await rtc.disableClockOutput();
        checkEq('clkout_disabled', conn.registers.get(_REG_TMR_CLKOUT_CTRL), 0x38);
    }

    // Battery backup: direct mode without low detection -> PM=101.
    {
        const conn = new I2CConnectionMock();
        const rtc = await full(conn);
        await rtc.configureBatteryBackup('direct', false);
        checkEq('battery_backup_direct', conn.registers.get(_REG_CONTROL_3) & 0xE0, 0xA0);
    }

    // pollInterrupt: maps every flag and clears only the flags that were set.
    {
        const conn = new I2CConnectionMock();
        const rtc = await full(conn);
        conn.setRegister(_REG_CONTROL_2, [0x80 | 0x20 | 0x08 | 0x03]);
        conn.setRegister(_REG_CONTROL_3, [0x08 | 0x04 | 0x02]);
        const status = await rtc.pollInterrupt();
        const S = PCF8523Full;
        checkEq('poll_interrupt_status', status,
            S.SOURCE_TIMER_A | S.SOURCE_TIMER_B | S.SOURCE_ALARM | S.SOURCE_BATTERY_SWITCH | S.SOURCE_BATTERY_LOW);
        const c2 = lastWrite(conn, _REG_CONTROL_2);
        checkEq('poll_interrupt_clears_set_flags', c2 & 0x28, 0);
        checkEq('poll_interrupt_keeps_unset_flags', c2 & 0x50, 0x50);
        checkEq('poll_interrupt_keeps_enables', c2 & 0x07, 0x03);
        checkEq('poll_interrupt_clears_bsf', lastWrite(conn, _REG_CONTROL_3), 0x02);
    }

    // enableInterrupt: SIE/AIE in CONTROL_1, BLIE in CONTROL_3.
    {
        const conn = new I2CConnectionMock();
        const rtc = await full(conn);
        await rtc.enableInterrupt(PCF8523Full.SOURCE_SECOND | PCF8523Full.SOURCE_ALARM | PCF8523Full.SOURCE_BATTERY_LOW);
        checkEq('enable_interrupt_control_1', lastWrite(conn, _REG_CONTROL_1), 0x06);
        checkEq('enable_interrupt_control_3', lastWrite(conn, _REG_CONTROL_3) & 0x03, 0x01);
        await rtc.disableInterrupt(PCF8523Full.SOURCE_ALARM);
        checkEq('disable_interrupt_control_1', lastWrite(conn, _REG_CONTROL_1), 0x04);
    }

    // softwareReset writes 0x58 to CONTROL_1.
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
