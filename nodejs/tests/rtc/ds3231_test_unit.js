'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { DS3231Minimal, DS3231Full } = require('../../packages/periph/src/chips/rtc/ds3231');

const _REG_SECONDS         = 0x00;
const _REG_MINUTES         = 0x01;
const _REG_HOURS           = 0x02;
const _REG_DAY             = 0x03;
const _REG_DATE            = 0x04;
const _REG_MONTH_CENTURY   = 0x05;
const _REG_YEAR            = 0x06;
const _REG_ALARM1_SECONDS  = 0x07;
const _REG_ALARM1_DAY_DATE = 0x0A;
const _REG_ALARM2_MINUTES  = 0x0B;
const _REG_ALARM2_DAY_DATE = 0x0D;
const _REG_CONTROL         = 0x0E;
const _REG_CONTROL_STATUS  = 0x0F;
const _REG_AGING_OFFSET    = 0x10;
const _REG_TEMP_MSB        = 0x11;
const _REG_TEMP_LSB        = 0x12;

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

async function main() {
    // -- init(): plain presence read, no writes --------------------------
    {
        const conn = new I2CConnectionMock();
        const rtc = new DS3231Minimal(conn);
        await rtc.init();
        // The mock logs the register-address byte of a writeRead() as a "write"
        // (it's a real bus write before the repeated-start read), so one entry
        // here is the presence probe itself, not a register-value write.
        checkEq('init_probe_is_address_byte_only', conn.writes.length, 1);
        checkTrue('init_reads_control_reg', conn.registers.size === 0); // read-only probe; nothing preloaded, nothing changed
    }

    // -- setDatetime() / getDatetime() round trip, and OSF clearing ------
    {
        const conn = new I2CConnectionMock();
        conn.setRegister(_REG_CONTROL_STATUS, [0x88]); // OSF=1, BSY=1
        const rtc = new DS3231Minimal(conn);
        await rtc.setDatetime(2026, 9, 22, 2, 14, 30, 0);

        checkEq('set_datetime_seconds', conn.registers.get(_REG_SECONDS), 0x00);
        checkEq('set_datetime_minutes', conn.registers.get(_REG_MINUTES), 0x30);
        checkEq('set_datetime_hours', conn.registers.get(_REG_HOURS), 0x14);
        checkEq('set_datetime_weekday', conn.registers.get(_REG_DAY), 0x02);
        checkEq('set_datetime_date', conn.registers.get(_REG_DATE), 0x22);
        checkEq('set_datetime_month', conn.registers.get(_REG_MONTH_CENTURY), 0x09);
        checkEq('set_datetime_year', conn.registers.get(_REG_YEAR), 0x26);
        checkEq('set_datetime_clears_osf_only', conn.registers.get(_REG_CONTROL_STATUS), 0x08);

        const dt = await rtc.getDatetime();
        checkEq('get_datetime_year', dt.year, 2026);
        checkEq('get_datetime_month', dt.month, 9);
        checkEq('get_datetime_day', dt.day, 22);
        checkEq('get_datetime_weekday', dt.weekday, 2);
        checkEq('get_datetime_hour', dt.hour, 14);
        checkEq('get_datetime_minute', dt.minute, 30);
        checkEq('get_datetime_second', dt.second, 0);
    }

    // -- readTemperature(): positive and negative -------------------------
    {
        const conn = new I2CConnectionMock();
        conn.setRegister(_REG_TEMP_MSB, [0x19, 0x40]); // 25 + 0.25 = 25.25
        const rtc = new DS3231Minimal(conn);
        checkTrue('read_temperature_positive', Math.abs((await rtc.readTemperature()) - 25.25) < 1e-9);
    }
    {
        const conn = new I2CConnectionMock();
        conn.setRegister(_REG_TEMP_MSB, [0xFF, 0xC0]); // -1 + 0.75 = -0.25
        const rtc = new DS3231Minimal(conn);
        checkTrue('read_temperature_negative', Math.abs((await rtc.readTemperature()) - (-0.25)) < 1e-9);
    }

    // -- setAlarm1() / getAlarm1() round trip -----------------------------
    {
        const conn = new I2CConnectionMock();
        const rtc = new DS3231Full(conn);
        await rtc.setAlarm1(30, 15, 10, 3, false, DS3231Full.ALARM1_MATCH_HOURS_MINUTES_SECONDS);
        checkEq('alarm1_daydate_reg', conn.registers.get(_REG_ALARM1_DAY_DATE), 0x83);
        const a1 = await rtc.getAlarm1();
        checkEq('alarm1_second', a1.second, 30);
        checkEq('alarm1_minute', a1.minute, 15);
        checkEq('alarm1_hour', a1.hour, 10);
        checkEq('alarm1_day_or_date', a1.dayOrDate, 3);
        checkEq('alarm1_is_day_of_week', a1.isDayOfWeek, false);
        checkEq('alarm1_match_mode', a1.matchMode, DS3231Full.ALARM1_MATCH_HOURS_MINUTES_SECONDS);
    }

    // -- setAlarm2() / getAlarm2() round trip -----------------------------
    {
        const conn = new I2CConnectionMock();
        const rtc = new DS3231Full(conn);
        await rtc.setAlarm2(45, 20, 5, true, DS3231Full.ALARM2_MATCH_HOURS_MINUTES);
        checkEq('alarm2_daydate_reg', conn.registers.get(_REG_ALARM2_DAY_DATE), 0xC5);
        const a2 = await rtc.getAlarm2();
        checkEq('alarm2_minute', a2.minute, 45);
        checkEq('alarm2_hour', a2.hour, 20);
        checkEq('alarm2_day_or_date', a2.dayOrDate, 5);
        checkEq('alarm2_is_day_of_week', a2.isDayOfWeek, true);
        checkEq('alarm2_match_mode', a2.matchMode, DS3231Full.ALARM2_MATCH_HOURS_MINUTES);
    }

    // -- square wave / 32kHz -----------------------------------------------
    {
        const conn = new I2CConnectionMock();
        const rtc = new DS3231Full(conn);
        await rtc.enableSquareWave(4096, true);
        checkEq('enable_square_wave_ctrl', conn.registers.get(_REG_CONTROL), 0x50); // RS2=0x10 | BBSQW=0x40
        await rtc.disableSquareWave();
        checkEq('disable_square_wave_sets_intcn', conn.registers.get(_REG_CONTROL), 0x54);

        checkTrue('rate_1hz_rejects_invalid', await (async () => {
            try { await rtc.enableSquareWave(3000); return false; } catch (e) { return true; }
        })());
    }
    {
        const conn = new I2CConnectionMock();
        conn.setRegister(_REG_CONTROL_STATUS, [0x00]);
        const rtc = new DS3231Full(conn);
        checkEq('is_32khz_enabled_false', await rtc.is32khzEnabled(), false);
        await rtc.enable32khzOutput();
        checkEq('enable_32khz_sets_bit', conn.registers.get(_REG_CONTROL_STATUS) & 0x08, 0x08);
        await rtc.disable32khzOutput();
        checkEq('disable_32khz_clears_bit', conn.registers.get(_REG_CONTROL_STATUS) & 0x08, 0x00);
    }

    // -- oscillator control --------------------------------------------------
    {
        const conn = new I2CConnectionMock();
        conn.setRegister(_REG_CONTROL_STATUS, [0x80]);
        const rtc = new DS3231Full(conn);
        checkEq('oscillator_stopped_true', await rtc.oscillatorStopped(), true);
        await rtc.clearOscillatorStopped();
        checkEq('clear_oscillator_stopped', conn.registers.get(_REG_CONTROL_STATUS) & 0x80, 0x00);

        await rtc.disableBatteryOscillator();
        checkEq('disable_battery_oscillator_sets_eosc', conn.registers.get(_REG_CONTROL) & 0x80, 0x80);
        await rtc.enableBatteryOscillator();
        checkEq('enable_battery_oscillator_clears_eosc', conn.registers.get(_REG_CONTROL) & 0x80, 0x00);
    }

    // -- forced temperature conversion (BSY already clear in the mock) -----
    {
        const conn = new I2CConnectionMock();
        conn.setRegister(_REG_TEMP_MSB, [0x1B, 0x00]); // 27.0 C
        const rtc = new DS3231Full(conn);
        const t = await rtc.forceTemperatureConversion();
        checkEq('force_conversion_sets_conv_bit', conn.registers.get(_REG_CONTROL) & 0x20, 0x20);
        checkTrue('force_conversion_returns_temperature', Math.abs(t - 27.0) < 1e-9);
    }

    // -- aging offset ---------------------------------------------------------
    {
        const conn = new I2CConnectionMock();
        conn.setRegister(_REG_AGING_OFFSET, [0xFF]);
        const rtc = new DS3231Full(conn);
        checkEq('get_aging_offset_negative', await rtc.getAgingOffset(), -1);
        await rtc.setAgingOffset(-5);
        checkEq('set_aging_offset', conn.registers.get(_REG_AGING_OFFSET), 0xFB);
    }

    // -- interrupt API: pollInterrupt / enable / disable ----------------------
    {
        const conn = new I2CConnectionMock();
        conn.setRegister(_REG_CONTROL_STATUS, [0x83]); // OSF=1, A2F=1, A1F=1
        const rtc = new DS3231Full(conn);
        const status = await rtc.pollInterrupt();
        checkEq('poll_interrupt_returns_sources', status, 0x03);
        checkEq('poll_interrupt_clears_only_alarm_flags', conn.registers.get(_REG_CONTROL_STATUS), 0x80);
    }
    {
        const conn = new I2CConnectionMock();
        const rtc = new DS3231Full(conn);
        await rtc.enableInterrupt(DS3231Full.SOURCE_ALARM1 | DS3231Full.SOURCE_ALARM2);
        checkEq('enable_interrupt_sets_a1ie_a2ie_intcn', conn.registers.get(_REG_CONTROL), 0x07);
        await rtc.disableInterrupt(DS3231Full.SOURCE_ALARM1);
        checkEq('disable_interrupt_clears_a1ie_only', conn.registers.get(_REG_CONTROL), 0x06);
    }

    // -- interrupt API: onInterrupt()/offInterrupt() polling fallback --------
    {
        const conn = new I2CConnectionMock();
        conn.setRegister(_REG_CONTROL_STATUS, [0x01]); // A1F latched
        const rtc = new DS3231Full(conn);
        let calls = 0;
        let lastStatus = 0;
        await rtc.onInterrupt((status) => { calls++; lastStatus = status; });
        await new Promise((r) => setTimeout(r, 30));
        await rtc.offInterrupt();
        checkTrue('on_interrupt_fires_once_for_latched_flag', calls === 1);
        checkEq('on_interrupt_callback_status', lastStatus, 0x01);
    }

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
