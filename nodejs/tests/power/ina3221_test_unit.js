'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { INA3221Full } = require('../../packages/periph/src/chips/power/ina3221');

const _REG_CONFIG = 0x00;
const _REG_SHUNT1 = 0x01;
const _REG_BUS1 = 0x02;
const _REG_SHUNT2 = 0x03;
const _REG_BUS2 = 0x04;
const _REG_CH2_CRIT = 0x09;
const _REG_CH1_WARN = 0x08;
const _REG_SUM = 0x0D;
const _REG_SUM_LIMIT = 0x0E;
const _REG_MASK_EN = 0x0F;
const _REG_PV_UPPER = 0x10;
const _REG_PV_LOWER = 0x11;
const _REG_MFR_ID = 0xFE;
const _REG_DIE_ID = 0xFF;

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

async function main() {
    const connection = new I2CConnectionMock();

    // Construction (default rShunt=0.1 for all 3 channels) writes nothing.
    const sensor = new INA3221Full(connection);
    checkTrue('init_writes_nothing', connection.writes.length === 0);

    // --- Channel 1 ---
    // Bus1 raw=10000 (0x2710) -> (10000>>3)*8e-3 = 10.0 V
    connection.setRegister(_REG_BUS1, [0x27, 0x10]);
    checkTrue('voltage_ch1', (await sensor.voltage(1)) === 10.0);

    // Shunt1 raw signed = -400 (0xFE70) -> -400 * 5e-6 = -0.002 V
    connection.setRegister(_REG_SHUNT1, [0xFE, 0x70]);
    checkTrue('shunt_voltage_ch1', Math.abs((await sensor.shuntVoltage(1)) - (-0.002)) < 1e-9);
    checkTrue('current_ch1', Math.abs((await sensor.current(1)) - (-0.02)) < 1e-9);

    // power(1): SHUNT1 (0x01) and BUS1 (0x02) are adjacent registers, and the
    // mock's byte-slot model can't hold two independent 16-bit values across
    // adjacent addresses at once (writing one clobbers the shared byte slot) -
    // so the SHUNT1 low byte and BUS1 high byte are chosen equal (0x10) to
    // survive either write order. SHUNT1=0xFF10 (-240 signed) -> -0.0012 V;
    // BUS1=0x1000 (4096) -> 4.096 V.
    connection.setRegister(_REG_SHUNT1, [0xFF, 0x10]);
    connection.setRegister(_REG_BUS1, [0x10, 0x00]);
    checkTrue('power_ch1', Math.abs((await sensor.power(1)) - (4.096 * -0.012)) < 1e-9);

    // --- Channel 2 ---
    // Bus2 raw=4096 (0x1000) -> (4096>>3)*8e-3 = 4.096 V
    connection.setRegister(_REG_BUS2, [0x10, 0x00]);
    checkTrue('voltage_ch2', (await sensor.voltage(2)) === 4.096);

    // Shunt2 raw=800 (0x0320) -> 800 * 5e-6 = 0.004 V
    connection.setRegister(_REG_SHUNT2, [0x03, 0x20]);
    checkTrue('shunt_voltage_ch2', Math.abs((await sensor.shuntVoltage(2)) - 0.004) < 1e-9);
    checkTrue('current_ch2', Math.abs((await sensor.current(2)) - 0.04) < 1e-9);

    // power(2): same adjacent-register overlap as power(1); SHUNT2 low byte
    // and BUS2 high byte chosen equal (0x08). SHUNT2=0x0108 (264) -> 0.00132 V;
    // BUS2=0x0800 (2048) -> 2.048 V.
    connection.setRegister(_REG_SHUNT2, [0x01, 0x08]);
    connection.setRegister(_REG_BUS2, [0x08, 0x00]);
    checkTrue('power_ch2', Math.abs((await sensor.power(2)) - (2.048 * 0.0132)) < 1e-9);

    // Invalid channel throws.
    let raisedInvalidChannel = false;
    try {
        await sensor.voltage(4);
    } catch (e) {
        raisedInvalidChannel = true;
    }
    checkTrue('invalid_channel_throws', raisedInvalidChannel);

    // configure(avg=3, vbusCt=2, vshCt=1, mode=5) preserves channel-enable
    // bits (0x7000) from the current Configuration Register.
    connection.setRegister(_REG_CONFIG, [0x71, 0x27]);
    await sensor.configure(3, 2, 1, 5);
    const configWrite1 = connection.writes.filter((w) => w.length === 3 && w[0] === _REG_CONFIG).pop();
    checkTrue('configure', configWrite1[1] === 0x76 && configWrite1[2] === 0x8D);

    // enableChannel(2, true): CH2en is bit 13.
    connection.setRegister(_REG_CONFIG, [0x01, 0x27]);
    await sensor.enableChannel(2, true);
    const configWrite2 = connection.writes.filter((w) => w.length === 3 && w[0] === _REG_CONFIG).pop();
    checkTrue('enable_channel', configWrite2[1] === 0x21 && configWrite2[2] === 0x27);

    // channelEnabled(1): CH1en is bit 14.
    connection.setRegister(_REG_CONFIG, [0x41, 0x27]);
    checkTrue('channel_enabled', (await sensor.channelEnabled(1)) === true);

    // conversionReady(): CVRF is bit 0.
    connection.setRegister(_REG_MASK_EN, [0x00, 0x01]);
    checkTrue('conversion_ready', (await sensor.conversionReady()) === true);

    // setCriticalAlert(channel=2, limitV=0.048, latch=true):
    // raw = (floor(0.048/40e-6) << 3) & 0xFFF8 = (1200 << 3) & 0xFFF8 = 0x2580.
    connection.setRegister(_REG_MASK_EN, [0x00, 0x00]);
    await sensor.setCriticalAlert(2, 0.048, true);
    const critWrite = connection.writes.filter((w) => w.length === 3 && w[0] === _REG_CH2_CRIT).pop();
    const critLatchWrite = connection.writes.filter((w) => w.length === 3 && w[0] === _REG_MASK_EN).pop();
    checkTrue('set_critical_alert_limit', critWrite[1] === 0x25 && critWrite[2] === 0x80);
    checkTrue('set_critical_alert_latch', critLatchWrite[1] === 0x04 && critLatchWrite[2] === 0x00);

    // setWarningAlert(channel=1, limitV=0.024, latch=false):
    // raw = (floor(0.024/40e-6) << 3) & 0xFFF8 = (600 << 3) & 0xFFF8 = 0x12C0.
    connection.setRegister(_REG_MASK_EN, [0x04, 0x00]);
    await sensor.setWarningAlert(1, 0.024, false);
    const warnWrite = connection.writes.filter((w) => w.length === 3 && w[0] === _REG_CH1_WARN).pop();
    const warnLatchWrite = connection.writes.filter((w) => w.length === 3 && w[0] === _REG_MASK_EN).pop();
    checkTrue('set_warning_alert_limit', warnWrite[1] === 0x12 && warnWrite[2] === 0xC0);
    checkTrue('set_warning_alert_latch', warnLatchWrite[1] === 0x04 && warnLatchWrite[2] === 0x00);

    // alertFlags(): raw Mask/Enable register.
    connection.setRegister(_REG_MASK_EN, [0x02, 0x41]);
    checkTrue('alert_flags', (await sensor.alertFlags()) === 0x0241);

    // setSummationChannels([1], limitV=0.1) with a stale SCC3 bit (0x1000)
    // already set: the fix must clear bits 14:12 (0x7000), not just 15:13
    // (0xE000), or SCC3 would incorrectly survive; and channel 1 must map to
    // bit 14 (SCC1), not the reserved bit 15.
    connection.setRegister(_REG_MASK_EN, [0x10, 0x00]);
    await sensor.setSummationChannels([1], 0.1);
    const summationMaskWrite = connection.writes.filter((w) => w.length === 3 && w[0] === _REG_MASK_EN).pop();
    const summationLimitWrite = connection.writes.filter((w) => w.length === 3 && w[0] === _REG_SUM_LIMIT).pop();
    checkTrue('set_summation_channels_clears_stale_scc3', summationMaskWrite[1] === 0x40 && summationMaskWrite[2] === 0x00);
    checkTrue('set_summation_channels_limit', summationLimitWrite[1] === 0x13 && summationLimitWrite[2] === 0x88);

    // summationValue(): raw=0x2328 (9000) -> 9000 * 20e-6 = 0.18 V.
    connection.setRegister(_REG_SUM, [0x23, 0x28]);
    checkTrue('summation_value', Math.abs((await sensor.summationValue()) - 0.18) < 1e-9);

    // setPowerValidLimits(upperV=8.112, lowerV=4.096):
    // rawUpper = (floor(8.112/8e-3) << 3) & 0xFFF8 = (1014 << 3) & 0xFFF8 = 0x1FB0
    // rawLower = (floor(4.096/8e-3) << 3) & 0xFFF8 = (512 << 3) & 0xFFF8 = 0x1000
    await sensor.setPowerValidLimits(8.112, 4.096);
    const pvUpperWrite = connection.writes.filter((w) => w.length === 3 && w[0] === _REG_PV_UPPER).pop();
    const pvLowerWrite = connection.writes.filter((w) => w.length === 3 && w[0] === _REG_PV_LOWER).pop();
    checkTrue('set_power_valid_upper', pvUpperWrite[1] === 0x1F && pvUpperWrite[2] === 0xB0);
    checkTrue('set_power_valid_lower', pvLowerWrite[1] === 0x10 && pvLowerWrite[2] === 0x00);

    // powerValid(): PVF is bit 2.
    connection.setRegister(_REG_MASK_EN, [0x00, 0x04]);
    checkTrue('power_valid', (await sensor.powerValid()) === true);

    // shutdown(): reads CONFIG, saves MODE bits, writes CONFIG & 0xFFF8.
    connection.setRegister(_REG_CONFIG, [0x71, 0x27]);
    await sensor.shutdown();
    const shutdownWrite = connection.writes[connection.writes.length - 1];
    checkTrue('shutdown', shutdownWrite[0] === _REG_CONFIG && shutdownWrite[1] === 0x71 && shutdownWrite[2] === 0x20);

    // wake(): reads CONFIG, restores saved MODE bits.
    connection.setRegister(_REG_CONFIG, [0x71, 0x20]);
    await sensor.wake();
    const wakeWrite = connection.writes[connection.writes.length - 1];
    checkTrue('wake', wakeWrite[0] === _REG_CONFIG && wakeWrite[1] === 0x71 && wakeWrite[2] === 0x27);

    // reset(): writes CONFIG = 0x8000 (RST bit) only.
    await sensor.reset();
    const resetWrite = connection.writes[connection.writes.length - 1];
    checkTrue('reset', resetWrite[0] === _REG_CONFIG && resetWrite[1] === 0x80 && resetWrite[2] === 0x00);

    // manufacturerId() / dieId()
    connection.setRegister(_REG_MFR_ID, [0x54, 0x49]);
    checkTrue('manufacturer_id', (await sensor.manufacturerId()) === 0x5449);
    connection.setRegister(_REG_DIE_ID, [0x32, 0x20]);
    checkTrue('die_id', (await sensor.dieId()) === 0x3220);

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
