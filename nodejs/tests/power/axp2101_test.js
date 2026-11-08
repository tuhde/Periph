'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { AXP2101Minimal, AXP2101Full } = require('../../packages/periph/src/chips/power/axp2101');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x34', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
const chip = new AXP2101Minimal(connection);
const full = new AXP2101Full(connection);

let passed = 0, failed = 0;
function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

(async () => {
    try {
        const v = await chip.batteryVoltage();
        checkTrue('battery_voltage_plausible', v >= 0 && v <= 5);
        const pct = await chip.batteryPercent();
        checkTrue('battery_percent_range', pct >= 0 && pct <= 100);
        const state = await chip.chargeState();
        checkTrue('charge_state_range', state >= 0 && state <= 5);
        checkTrue('vbus_present_is_bool', typeof (await chip.vbusPresent()) === 'boolean');

        checkTrue('vbus_voltage_range', (await full.vbusVoltage()) <= 15);
        checkTrue('system_voltage_range', (await full.systemVoltage()) <= 6);
        checkTrue('ts_voltage_range', (await full.tsVoltage()) <= 4);
        const t = await full.dieTemperature();
        checkTrue('die_temperature_range', t >= -40 && t <= 150);
        const d = await full.readAll();
        checkTrue('read_all_keys', Object.keys(d).length === 8);
        const s = await full.status();
        checkTrue('status_direction', ['standby', 'charge', 'discharge', 'reserved'].includes(s.batteryDirection));

        const [enabled, volts] = await full.getRail('dcdc1');
        checkTrue('get_rail_dcdc1', typeof enabled === 'boolean' && volts >= 1.4 && volts <= 3.5);

        await full.setInputVoltageLimit(4.36);
        checkTrue('input_voltage_limit_roundtrip', ((await full._readReg(0x15)) & 0x0F) === 6);

        await full.writeDataBuffer(0, 0x5A);
        checkTrue('data_buffer_roundtrip', (await full.readDataBuffer(0)) === 0x5A);

        await full.enableInterrupt(AXP2101Full.SOURCE_VBUS_INSERT);
        checkTrue('poll_interrupt_int', Number.isInteger(await full.pollInterrupt()));
    } catch (e) {
        console.log('FAIL exception:', e.message);
        failed++;
    }
    await connection.close();
    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
})();
