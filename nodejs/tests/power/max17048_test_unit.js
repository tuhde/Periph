'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { MAX17048Full, MAX17048Minimal } = require('../../packages/periph/src/chips/power/max17048');

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

const near = (a, b, tol = 1e-9) => Math.abs(a - b) < tol;
const words = (c) => c.writes.filter((w) => w.length === 3);
const lastIs = (c, reg, msb, lsb) => {
    const w = words(c).pop();
    return !!w && w[0] === reg && w[1] === msb && w[2] === lsb;
};

async function main() {
    const conn = new I2CConnectionMock();
    const gauge = new MAX17048Full(conn);
    checkTrue('init_no_bus_access', conn.writes.length === 0);

    conn.setRegister(0x02, [0xC3, 0x50]);
    checkTrue('voltage', near(await gauge.voltage(), 3.90625));
    checkTrue('raw_voltage', (await gauge.rawVoltage()) === 0xC350);
    checkTrue('voltage_two_cells', near(await new MAX17048Minimal(conn, 2).voltage(), 7.8125));

    conn.setRegister(0x04, [0x4B, 0x80]);
    checkTrue('percent', near(await gauge.percent(), 75.5));
    checkTrue('raw_soc', (await gauge.rawSoc()) === 0x4B80);
    conn.setRegister(0x04, [0xFF, 0xFF]);
    checkTrue('percent_clamped', (await gauge.percent()) === 100);

    conn.setRegister(0x16, [0xFF, 0xEC]);
    checkTrue('charge_rate_negative', near(await gauge.chargeRate(), -4.16, 1e-9));
    conn.setRegister(0x16, [0x00, 0x64]);
    checkTrue('charge_rate', near(await gauge.chargeRate(), 20.8, 1e-9));

    conn.setRegister(0x08, [0x00, 0x12]);
    checkTrue('version', (await gauge.version()) === 0x0012);
    conn.setRegister(0x18, [0x96, 0xAB]);
    checkTrue('chip_id', (await gauge.chipId()) === 0xAB);

    conn.setRegister(0x0C, [0x97, 0x1C]);
    checkTrue('get_rcomp', (await gauge.getRcomp()) === 0x97);
    await gauge.setRcomp(0x80);
    checkTrue('set_rcomp_rmw', lastIs(conn, 0x0C, 0x80, 0x1C));
    conn.setRegister(0x0C, [0x97, 0x1C]);
    await gauge.compensateTemperature(35);
    checkTrue('compensate_hot', lastIs(conn, 0x0C, 0x90, 0x1C));
    await gauge.compensateTemperature(5);
    checkTrue('compensate_cold', lastIs(conn, 0x0C, 0xE2, 0x1C));

    conn.setRegister(0x0C, [0x97, 0x1C]);
    await gauge.setAlertSoc(10);
    checkTrue('set_alert_soc', lastIs(conn, 0x0C, 0x97, 0x16));
    checkTrue('get_alert_soc', (await gauge.getAlertSoc()) === 10);
    await gauge.setSocChangeAlert(true);
    checkTrue('soc_change_on', lastIs(conn, 0x0C, 0x97, 0x56));
    await gauge.setSocChangeAlert(false);
    checkTrue('soc_change_off', lastIs(conn, 0x0C, 0x97, 0x16));

    await gauge.setVoltageAlert(3.3, 4.2);
    checkTrue('set_voltage_alert', lastIs(conn, 0x14, 0xA5, 0xD2));
    const win = await gauge.getVoltageAlert();
    checkTrue('get_voltage_alert', near(win.minV, 3.3, 1e-9) && near(win.maxV, 4.2, 1e-9));
    await gauge.setVoltageAlert(0, 5.1);
    checkTrue('voltage_alert_disabled', lastIs(conn, 0x14, 0x00, 0xFF));
    let threw = false;
    try { await gauge.setVoltageAlert(4.0, 3.0); } catch (e) { threw = e instanceof RangeError; }
    checkTrue('voltage_alert_invalid', threw);

    conn.setRegister(0x18, [0x01, 0x5A]);
    await gauge.setResetVoltage(3.0);
    checkTrue('set_reset_voltage', lastIs(conn, 0x18, 0x97, 0x5A));
    checkTrue('get_reset_voltage', near(await gauge.getResetVoltage(), 3.0));
    await gauge.setComparatorDisabled(false);
    checkTrue('comparator_enabled', lastIs(conn, 0x18, 0x96, 0x5A));
    await gauge.setComparatorDisabled(true);
    checkTrue('comparator_disabled', lastIs(conn, 0x18, 0x97, 0x5A));

    conn.setRegister(0x1A, [0x01, 0x00]);
    await gauge.enableVoltageResetAlert(true);
    checkTrue('envr_on', lastIs(conn, 0x1A, 0x41, 0x00));
    await gauge.enableVoltageResetAlert(false);
    checkTrue('envr_off', lastIs(conn, 0x1A, 0x01, 0x00));

    await gauge.setHibernateThresholds(26.624, 0.06);
    checkTrue('set_hibernate', lastIs(conn, 0x0A, 0x80, 0x30));
    const hib = await gauge.getHibernateThresholds();
    checkTrue('get_hibernate', near(hib.hibThrPctPerH, 26.624, 1e-9) && near(hib.actThrV, 0.06, 1e-9));
    await gauge.disableHibernate();
    checkTrue('disable_hibernate', lastIs(conn, 0x0A, 0x00, 0x00));
    await gauge.forceHibernate();
    checkTrue('force_hibernate', lastIs(conn, 0x0A, 0xFF, 0xFF));

    conn.setRegister(0x06, [0x10, 0x00]);
    checkTrue('is_hibernating_true', (await gauge.isHibernating()) === true);
    conn.setRegister(0x06, [0x00, 0x00]);
    checkTrue('is_hibernating_false', (await gauge.isHibernating()) === false);
    await gauge.enableSleep(true);
    checkTrue('enable_sleep', lastIs(conn, 0x06, 0x20, 0x00));
    await gauge.quickStart();
    checkTrue('quick_start_keeps_ensleep', lastIs(conn, 0x06, 0x60, 0x00));
    await gauge.enableSleep(false);
    checkTrue('disable_sleep', lastIs(conn, 0x06, 0x00, 0x00));
    await gauge.quickStart();
    checkTrue('quick_start', lastIs(conn, 0x06, 0x40, 0x00));

    conn.setRegister(0x0C, [0x97, 0x1C]);
    await gauge.sleep();
    checkTrue('sleep', lastIs(conn, 0x0C, 0x97, 0x9C));
    await gauge.wake();
    checkTrue('wake', lastIs(conn, 0x0C, 0x97, 0x1C));

    checkTrue('read_config', (await gauge.readConfig()) === 0x971C);
    await gauge.writeConfig(0x9700);
    checkTrue('write_config', lastIs(conn, 0x0C, 0x97, 0x00));

    conn.setRegister(0x1A, [0x11, 0x00]);
    checkTrue('read_status', (await gauge.readStatus()) === 0x1100);
    conn.setRegister(0x0C, [0x97, 0x3C]);
    await gauge.clearStatus(0x0100);
    checkTrue('clear_status_status', conn.registers.get(0x1A) === 0x10 && conn.registers.get(0x1B) === 0x00);
    checkTrue('clear_status_alrt', lastIs(conn, 0x0C, 0x97, 0x1C));

    conn.setRegister(0x1A, [0x51, 0x00]);
    conn.setRegister(0x0C, [0x97, 0x3C]);
    checkTrue('poll_interrupt_flags', (await gauge.pollInterrupt()) === 0x1100);
    checkTrue('poll_interrupt_clears_status', conn.registers.get(0x1A) === 0x40);
    checkTrue('poll_interrupt_clears_alrt', conn.registers.get(0x0D) === 0x1C);
    const before = words(conn).length;
    checkTrue('poll_interrupt_none', (await gauge.pollInterrupt()) === 0 && words(conn).length === before);

    conn.setRegister(0x0C, [0x97, 0x1C]);
    await gauge.enableInterrupt(MAX17048Full.SOURCE_SOC_CHANGE);
    checkTrue('enable_soc_change', lastIs(conn, 0x0C, 0x97, 0x5C));
    await gauge.disableInterrupt(MAX17048Full.SOURCE_SOC_CHANGE);
    checkTrue('disable_soc_change', lastIs(conn, 0x0C, 0x97, 0x1C));
    conn.setRegister(0x14, [0xA5, 0xD2]);
    await gauge.disableInterrupt(MAX17048Full.SOURCE_UNDERVOLTAGE);
    checkTrue('disable_undervoltage', lastIs(conn, 0x14, 0x00, 0xD2));
    await gauge.disableInterrupt(MAX17048Full.SOURCE_OVERVOLTAGE);
    checkTrue('disable_overvoltage', lastIs(conn, 0x14, 0x00, 0xFF));

    let seen = 0;
    conn.setRegister(0x1A, [0x10, 0x00]);
    gauge._callback = (s) => { seen = s; };
    await gauge._service();
    checkTrue('service_callback', seen === 0x1000);
    await gauge.offInterrupt();
    checkTrue('off_interrupt', gauge._callback === null);

    conn.failNextWrites(1);
    await gauge.reset();
    checkTrue('reset', lastIs(conn, 0xFE, 0x54, 0x00));

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
