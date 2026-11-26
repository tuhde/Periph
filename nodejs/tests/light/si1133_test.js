'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { Si1133Full } = require('../../packages/periph/src/chips/light/si1133');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x55', 16);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else           { console.log('FAIL', label); failed++; }
}

const in24 = (v) => v >= -0x800000 && v <= 0x7FFFFF;

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const sensor = new Si1133Full(connection);
    await sensor.init();
    const id = await sensor.readId();
    checkTrue('part_id', id.partId === 0x33);
    checkTrue('hw_id_impl', (id.hwId & 0x1F) === 0x03);
    checkTrue('response_no_error', !(await sensor.readResponse()).error);
    const m = await sensor.read();
    checkTrue('read_uv_range', in24(m.uv));
    checkTrue('read_white_positive_in_light', m.white > 0);
    checkTrue('read_uv_scratch', in24(await sensor.readUv()));
    checkTrue('read_white_scratch', (await sensor.readWhite()) > 0);
    checkTrue('read_ir_small', in24(await sensor.readIr(Si1133Full.MUX_SMALL_IR)));
    checkTrue('read_ir_large', in24(await sensor.readIr(Si1133Full.MUX_LARGE_IR)));
    await sensor.configureChannel(2, Si1133Full.MUX_LARGE_IR, { hwGain: 5, bits24: false });
    await sensor.enableChannels(0x07);
    const values = await sensor.readChannels();
    checkTrue('read_channels_count', values.length === 3);
    checkTrue('read_channels_16bit', values[2] >= 0 && values[2] <= 0xFFFF);
    await sensor.writeParameter(0x1C, 0x05);
    checkTrue('parameter_roundtrip', (await sensor.readParameter(0x1C)) === 0x05);
    checkTrue('chan_list_readback', (await sensor.readParameter(0x01)) === 0x07);
    await sensor.setThreshold(0, 1000);
    await sensor.setChannelThreshold(0, 1);
    checkTrue('threshold_readback', (await sensor.readParameter(0x25)) === 0x03 && (await sensor.readParameter(0x26)) === 0xE8);
    await sensor.setChannelThreshold(0, 0);
    await sensor.enableChannels(0x03);
    await sensor.dataReady();
    await sensor.startAutonomous(0.1, [1, 1, 1]);
    await sleep(500);
    checkTrue('autonomous_irq', ((await sensor.pollInterrupt()) & 0x03) !== 0);
    checkTrue('autonomous_values', (await sensor.readChannels(false)).length === 2);
    await sensor.pause();
    await sensor.reset();
    checkTrue('reset_sleep', ((await sensor.readResponse()).state & Si1133Full.RESP_SLEEP) !== 0);
    checkTrue('read_after_reset', (await sensor.read()).white > 0);

    await connection.close();
    console.log('===DONE: ' + passed + ' passed, ' + failed + ' failed===');
    process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });
