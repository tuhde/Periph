'use strict';

const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { AXP2101Minimal, AXP2101Full } = require('../../packages/periph/src/chips/power/axp2101');

let passed = 0, failed = 0;
function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

// Mock writes accumulate; checks look at the most recent write to the register.
function lastWrite(mock, reg) {
    const w = [...mock.writes].reverse().find(x => x.length === 2 && x[0] === reg);
    return w ? w[1] : undefined;
}

async function raises(fn) {
    try { await fn(); } catch (e) { return e instanceof RangeError; }
    return false;
}

function make(regs = {}) {
    const m = new I2CConnectionMock();
    m.setRegister(0x30, [0x03]);   // ADC_ENABLE reset
    m.setRegister(0x18, [0x0A]);   // MODULE_ENABLE reset
    for (const [r, v] of Object.entries(regs)) m.setRegister(Number(r), Array.isArray(v) ? v : [v]);
    return m;
}

async function build(Cls, regs) {
    const m = make(regs);
    const chip = new Cls(m);
    await chip.ready;
    return [m, chip];
}

// Table-driven setter checks: [label, preset registers, action(f), expected [register, last write]...]
const CASES = [
    ['set_adc_channels', {}, f => f.setAdcChannels(true, false, true, false, false, true), [[0x30, 0x25]]],
    ['adc_low_frequency', { 0x36: 0 }, f => f.setAdcLowFrequency(true), [[0x36, 0x80]]],
    ['set_charging_off', { 0x18: 0x0A }, f => f.setCharging(false), [[0x18, 0x08]]],
    ['set_charging_on', { 0x18: 0x08 }, f => f.setCharging(true), [[0x18, 0x0A]]],
    ['charge_voltage_4v2', { 0x64: 0 }, f => f.setChargeVoltage(4.2), [[0x64, 3]]],
    ['charge_voltage_4v4', { 0x64: 0 }, f => f.setChargeVoltage(4.4), [[0x64, 5]]],
    ['charge_current_0.5A', { 0x64: 3 }, f => f.setChargeCurrent(0.5), [[0x62, 11]]],
    ['charge_current_0.1A', { 0x64: 3 }, f => f.setChargeCurrent(0.1), [[0x62, 4]]],
    ['charge_current_1.4A', { 0x64: 3 }, f => f.setChargeCurrent(1.4), [[0x62, 20]]],
    ['charge_current_1.5A_at_4v0', { 0x64: 1 }, f => f.setChargeCurrent(1.5), [[0x62, 21]]],
    ['precharge_current', { 0x61: 0xF0 }, f => f.setPrechargeCurrent(0.125), [[0x61, 0xF5]]],
    ['termination_enabled', { 0x63: 0xE0 }, f => f.setTermination(0.05, true), [[0x63, 0xF2]]],
    ['termination_disabled', { 0x63: 0xE0 }, f => f.setTermination(0.05, false), [[0x63, 0xE2]]],
    ['input_current_limit', {}, f => f.setInputCurrentLimit(2.0), [[0x16, 5]]],
    ['input_voltage_limit', {}, f => f.setInputVoltageLimit(4.36), [[0x15, 6]]],
    ['min_system_voltage', {}, f => f.setMinSystemVoltage(3.7), [[0x14, 5]]],
    ['charge_timers_default', { 0x67: 0x08 }, f => f.setChargeTimers(), [[0x67, 0xEE]]],
    ['charge_timers_custom', { 0x67: 0x08 }, f => f.setChargeTimers(70, 20, false, false, false), [[0x67, 0x3B]]],
    ['thermal_regulation', {}, f => f.setThermalRegulation(120), [[0x65, 3]]],
    ['die_protection', {}, f => f.setDieProtection(135, false), [[0x13, 0x04]]],
    ['battery_detection_off', { 0x68: 1 }, f => f.setBatteryDetection(false), [[0x68, 0]]],
    ['backup_charging', { 0x6A: 0xF8 }, f => f.setBackupCharging(true, 3.3), [[0x6A, 0xFF], [0x18, 0x0E]]],
    ['chgled_manual_blink_4hz', { 0x69: 0xC0 }, f => f.setChgled('manual', 'blink_4hz'), [[0x69, 0xE5]]],
    ['chgled_type_b', { 0x69: 0xC0 }, f => f.setChgled('type_b'), [[0x69, 0xC3]]],
    ['chgled_off', { 0x69: 0xC1 }, f => f.setChgled('off'), [[0x69, 0xC0]]],
    ['configure_ts', { 0x50: 0x20 }, f => f.configureTs(false, 60, 'always'), [[0x50, 0x3F]]],
    ['ts_limits', {}, f => f.setTsLimits(1.312, 0.176, 1.984, 0.152), [[0x54, 41], [0x55, 88], [0x56, 62], [0x57, 76]]],
    ['ts_hysteresis', {}, f => f.setTsHysteresis(0.032, 0.004), [[0x52, 2], [0x53, 1]]],
    ['jeita', { 0x59: 0x80 }, f => f.configureJeita(true, 0.8, 0.24), [[0x59, 0x94], [0x5A, 50], [0x5B, 30], [0x58, 1]]],
    ['rail_dcdc2_high_range', { 0x83: 0x80 }, f => f.setRailVoltage('dcdc2', 1.5), [[0x83, 0x80 | 85]]],
    ['rail_dcdc2_1v2', { 0x83: 0x80 }, f => f.setRailVoltage('dcdc2', 1.2), [[0x83, 0x80 | 70]]],
    ['rail_dcdc1_3v3', {}, f => f.setRailVoltage('dcdc1', 3.3), [[0x82, 18]]],
    ['rail_dcdc4_max', {}, f => f.setRailVoltage('dcdc4', 1.84), [[0x85, 102]]],
    ['rail_aldo2_3v3', {}, f => f.setRailVoltage('aldo2', 3.3), [[0x93, 28]]],
    ['rail_cpusldo_1v0', {}, f => f.setRailVoltage('cpusldo', 1.0), [[0x98, 10]]],
    ['rail_dldo1_3v3', {}, f => f.setRailVoltage('dldo1', 3.3), [[0x99, 28]]],
    ['rail_dldo2_1v4', {}, f => f.setRailVoltage('dldo2', 1.4), [[0x9A, 18]]],
    ['rail_enable_bldo2', { 0x90: 0 }, f => f.setRailEnabled('bldo2', true), [[0x90, 0x20]]],
    ['rail_enable_dldo1_keeps_bldo2', { 0x90: 0x20 }, f => f.setRailEnabled('dldo1', true), [[0x90, 0xA0]]],
    ['rail_disable_bldo2', { 0x90: 0xA0 }, f => f.setRailEnabled('bldo2', false), [[0x90, 0x80]]],
    ['rail_enable_dldo2', { 0x91: 0 }, f => f.setRailEnabled('dldo2', true), [[0x91, 0x01]]],
    ['dcdc3_always_pwm_dvm', { 0x81: 0 }, f => f.configureDcdc('dcdc3', true, true), [[0x81, 0x10], [0x84, 0x80]]],
    ['dcdc_global', { 0x80: 0x0F, 0x81: 0x10 }, f => f.configureDcdcGlobal(true, true, true, true, 240), [[0x80, 0x6F], [0x81, 0xD3]]],
    ['off_discharge_off_keeps_reserved', { 0x10: 0x30 }, f => f.setOffDischarge(false), [[0x10, 0x10]]],
    ['power_off_sets_bit0', { 0x10: 0x30 }, f => f.powerOff(), [[0x10, 0x31]]],
    ['restart_sets_bit1', { 0x10: 0x30 }, f => f.restart(), [[0x10, 0x32]]],
    ['sleep_bit0', { 0x26: 0x08 }, f => f.sleep(), [[0x26, 0x09]]],
    ['wakeup_bit1', { 0x26: 0x08 }, f => f.wakeup(), [[0x26, 0x0A]]],
    ['configure_sleep', { 0x26: 0x03 }, f => f.configureSleep(true, false, true), [[0x26, 0x17]]],
    ['power_key', { 0x27: 0xC0, 0x22: 0x04, 0x10: 0x30 }, f => f.configurePowerKey(128, 10, 2.5, true, true, true),
        [[0x27, 0xFC], [0x22, 0x07], [0x10, 0x34]]],
    ['power_off_config', { 0x22: 0x03, 0x23: 0 }, f => f.configurePowerOff(false, 3.3, true, 0x05), [[0x22, 0x03], [0x24, 7], [0x23, 0x25]]],
    ['pwrok', { 0x25: 0x20, 0x10: 0x30 }, f => f.configurePwrok(true, 64, true, true, true), [[0x25, 0x3F], [0x10, 0x38]]],
    ['batfet_off_enable', { 0x12: 0 }, f => f.setBatfetOffEnable(true), [[0x12, 0x08]]],
    ['fast_poweron', { 0x28: 0xFF, 0x2B: 0x0F }, f => f.configureFastPoweron(true, false, { dcdc1: 1, dcdc4: null, dldo2: 2 }),
        [[0x28, 0xFD], [0x2B, 0x8B]]],
    ['watchdog', { 0x19: 0, 0x18: 0x0A }, f => f.configureWatchdog(true, 128, 'power_cycle'), [[0x19, 0x37], [0x18, 0x0B]]],
    ['feed_watchdog', { 0x19: 0x06 }, f => f.feedWatchdog(), [[0x19, 0x0E]]],
    ['battery_warning', {}, f => f.configureBatteryWarning(15, 5), [[0x1A, 0xA5]]],
    ['gauge_enable', { 0x18: 0x02 }, f => f.setGaugeEnabled(true), [[0x18, 0x0A]]],
    ['reset_gauge', { 0x17: 0 }, f => f.resetGauge(), [[0x17, 0x08]]],
    ['reset_gauge_full', { 0x17: 0 }, f => f.resetGauge(true), [[0x17, 0x04]]],
    ['gpio1_low', { 0x1B: 0 }, f => f.setGpio1(true), [[0x1B, 0x04]]],
    ['gpio1_hiz', { 0x1B: 0x04 }, f => f.setGpio1(false), [[0x1B, 0x00]]],
    ['data_buffer_write', {}, f => f.writeDataBuffer(2, 0xAB), [[0x06, 0xAB]]],
    ['irq_disable', { 0x40: [0xFF, 0xFC, 0x5F] },
        f => f.disableInterrupt(AXP2101Full.SOURCE_VBUS_INSERT | AXP2101Full.SOURCE_CHARGE_DONE), [[0x41, 0x7C], [0x42, 0x4F]]],
    ['irq_enable', { 0x40: [0x00, 0x7C, 0x4F] },
        f => f.enableInterrupt(AXP2101Full.SOURCE_VBUS_INSERT | AXP2101Full.SOURCE_PWRON_SHORT), [[0x41, 0xFC]]],
    ['irq_enable_en0_only', { 0x40: [0, 0, 0] }, f => f.enableInterrupt(AXP2101Full.SOURCE_SOC_WARN_L2), [[0x40, 0x80]]],
];

// Setters that must reject their argument with a RangeError.
const REJECTS = [
    ['charge_voltage_invalid', f => f.setChargeVoltage(4.25)],
    ['charge_current_over_cv_limit', f => f.setChargeCurrent(1.5)],
    ['charge_current_negative', f => f.setChargeCurrent(-0.1)],
    ['precharge_out_of_range', f => f.setPrechargeCurrent(0.3)],
    ['input_current_limit_invalid', f => f.setInputCurrentLimit(1.2)],
    ['jeita_bad_drop', f => f.configureJeita(true, null, null, true, false, 3, 0)],
    ['rail_dcdc3_over_max', f => f.setRailVoltage('dcdc3', 1.84)],
    ['rail_dldo1_over', f => f.setRailVoltage('dldo1', 3.5)],
    ['rail_unknown', f => f.setRailVoltage('foo', 1.0)],
    ['rail_below_range', f => f.setRailVoltage('aldo1', 0.3)],
    ['dcdc1_dvm_rejected', f => f.configureDcdc('dcdc1', false, true)],
    ['power_key_bad_value', f => f.configurePowerKey(100)],
    ['fast_poweron_unknown_rail', f => f.configureFastPoweron(true, true, { x: 1 })],
    ['watchdog_bad_timeout', f => f.configureWatchdog(true, 3)],
    ['battery_warning_range', f => f.configureBatteryWarning(25, 1)],
    ['data_buffer_index', f => f.readDataBuffer(4)],
];

async function main() {
    // --- Minimal ---
    let [m, chip] = await build(AXP2101Minimal, { 0x34: [0x0F, 0xA0], 0xA4: 87, 0x00: 0x20, 0x01: 0x22 });
    checkTrue('init_adc_enable_0x1F', lastWrite(m, 0x30) === 0x1F);
    checkTrue('init_module_gauge_bit', lastWrite(m, 0x18) === 0x0A);
    checkTrue('battery_voltage', Math.abs(await chip.batteryVoltage() - 4.0) < 1e-9);
    checkTrue('battery_percent', (await chip.batteryPercent()) === 87);
    checkTrue('charge_state_cc', (await chip.chargeState()) === AXP2101Minimal.CHARGE_CC);
    checkTrue('vbus_present_true', (await chip.vbusPresent()) === true);
    m.setRegister(0x00, [0x00]);
    checkTrue('vbus_present_false', (await chip.vbusPresent()) === false);

    [m, chip] = await build(AXP2101Minimal, { 0x18: 0x02 });
    checkTrue('init_gauge_set_preserves_bits', lastWrite(m, 0x18) === 0x0A);

    [m, chip] = await build(AXP2101Minimal, { 0x34: [0xCF, 0xA0] });
    checkTrue('adc_masks_debug_bits', Math.abs(await chip.batteryVoltage() - 4.0) < 1e-9);

    // Bus check failure surfaces through ready.
    const dead = make();
    dead.writeRead = async () => { throw new Error('nack'); };
    let rejected = false;
    try { await new AXP2101Minimal(dead).ready; } catch (e) { rejected = /not responding/.test(e.message); }
    checkTrue('init_bus_failure_rejects', rejected);

    // --- Full measurements ---
    [m, chip] = await build(AXP2101Full, {
        0x34: [0x0F, 0xA0], 0x36: [0x03, 0xE8], 0x38: [0x13, 0x88], 0x3A: [0x10, 0x68], 0x3C: [0x1C, 0x6A], 0xA4: 50,
    });
    checkTrue('vbus_voltage', Math.abs(await chip.vbusVoltage() - 5.0) < 1e-9);
    checkTrue('system_voltage', Math.abs(await chip.systemVoltage() - 4.2) < 1e-9);
    checkTrue('ts_voltage', Math.abs(await chip.tsVoltage() - 0.5) < 1e-9);
    checkTrue('die_temperature', Math.abs(await chip.dieTemperature() - 22.0) < 1e-9);
    const d = await chip.readAll();
    checkTrue('read_all_keys', Object.keys(d).length === 8 && d.batteryPercent === 50);
    m.setRegister(0x00, [0x3F]);
    m.setRegister(0x01, [0x4B]);
    const s = await chip.status();
    checkTrue('status_flags', s.vbusGood && s.batfetOpen && s.batteryPresent && s.batteryActive &&
        s.thermalRegulation && s.currentLimit);
    checkTrue('status_direction_discharge', s.batteryDirection === 'discharge');
    checkTrue('status_system_on_clear_vindpm', !s.systemOn && s.vindpm && s.chargeState === 3);

    m.setRegister(0x80, [0x0F]);
    m.setRegister(0x83, [0x80 | 18]);
    const [en, v] = await chip.getRail('dcdc2');
    checkTrue('get_rail_dcdc2', en && Math.abs(v - 0.68) < 1e-9);

    // --- Setter cases ---
    for (const [label, regs, action, expected] of CASES) {
        const [mm, ff] = await build(AXP2101Full, regs);
        mm.writes.length = 0;
        await action(ff);
        checkTrue(label, expected.every(([reg, val]) => lastWrite(mm, reg) === val));
    }
    for (const [label, action] of REJECTS) {
        const [, ff] = await build(AXP2101Full, { 0x64: 3 });
        checkTrue(label, await raises(() => action(ff)));
    }

    // Data buffer round trip.
    [m, chip] = await build(AXP2101Full, {});
    await chip.writeDataBuffer(2, 0xAB);
    checkTrue('data_buffer_read', (await chip.readDataBuffer(2)) === 0xAB);

    // Power-on / power-off flags.
    [m, chip] = await build(AXP2101Full, { 0x20: 0x21, 0x21: 0x81 });
    checkTrue('power_on_source', (await chip.powerOnSource()) === 0x21);
    checkTrue('power_off_source', (await chip.powerOffSource()) === 0x81);

    // --- Interrupt status ---
    [m, chip] = await build(AXP2101Full, { 0x48: [0x10, 0x88, 0x01] });
    m.writes.length = 0;
    const st = await chip.pollInterrupt();
    const wrote = (a, b) => m.writes.filter(w => w.length === 2 && w[0] === a && w[1] === b).length;
    checkTrue('poll_interrupt_value', st === 0x018810);
    checkTrue('poll_interrupt_clears_seen_bits', wrote(0x48, 0x10) === 1 && wrote(0x49, 0x88) === 1 && wrote(0x4A, 0x01) === 1);
    m.setRegister(0x48, [0, 0, 0]);
    m.writes.length = 0;
    checkTrue('poll_interrupt_idle', (await chip.pollInterrupt()) === 0 &&
        m.writes.filter(w => w.length === 2 && w[0] >= 0x48 && w[0] <= 0x4A).length === 0);

    // onInterrupt polling fallback delivers a pending event, offInterrupt stops it.
    [m, chip] = await build(AXP2101Full, { 0x49: 0x80 });
    const got = await new Promise(resolve => {
        chip.onInterrupt(status => resolve(status));
    });
    await chip.offInterrupt();
    checkTrue('on_interrupt_polling_delivers', got === AXP2101Full.SOURCE_VBUS_INSERT);

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main().catch(e => { console.log('FAIL exception:', e.stack); process.exit(1); });
