'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { MB85RCMinimal, MB85RCFull } = require('../../packages/periph/src/chips/memory/mb85rc');

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

function eq(a, b) { return Buffer.compare(Buffer.from(a), Buffer.from(b)) === 0; }

async function rejects(fn) {
    try { await fn(); } catch (e) { return e instanceof RangeError; }
    return false;
}

async function main() {
    const connection = new I2CConnectionMock();
    connection.setAddressWidth(2);
    const fram = new MB85RCFull(connection);
    checkTrue('init_no_traffic', connection.writes.length === 0);
    checkTrue('size', fram.size() === 32768);

    connection.setRegister(0x1234, [0x42]);
    checkTrue('read_byte', (await fram.readByte(0x1234)) === 0x42);
    checkTrue('read_byte_address_bytes', eq(connection.writes[connection.writes.length - 1], [0x12, 0x34]));

    await fram.writeByte(0x0010, 0x99);
    checkTrue('write_byte', connection.registers.get(0x0010) === 0x99);
    checkTrue('write_byte_frame', eq(connection.writes[connection.writes.length - 1], [0x00, 0x10, 0x99]));

    checkTrue('minimal_class', new MB85RCMinimal(connection) instanceof MB85RCMinimal);
    checkTrue('read_byte_range_hi', await rejects(() => fram.readByte(0x8000)));
    checkTrue('read_byte_range_neg', await rejects(() => fram.readByte(-1)));
    checkTrue('write_byte_range', await rejects(() => fram.writeByte(0x8000, 1)));

    checkTrue('max_chunk_default', fram.maxChunk === 30);
    const data = Buffer.from(Array.from({ length: 70 }, (_, i) => i));
    connection.writes.length = 0;
    await fram.write(0x0100, data);
    checkTrue('write_chunk_count', connection.writes.length === 3);
    checkTrue('write_chunk_0', eq(connection.writes[0], Buffer.concat([Buffer.from([0x01, 0x00]), data.subarray(0, 30)])));
    checkTrue('write_chunk_1', eq(connection.writes[1], Buffer.concat([Buffer.from([0x01, 30]), data.subarray(30, 60)])));
    checkTrue('write_chunk_2', eq(connection.writes[2], Buffer.concat([Buffer.from([0x01, 60]), data.subarray(60)])));
    connection.writes.length = 0;
    checkTrue('read_roundtrip', eq(await fram.read(0x0100, 70), data));
    checkTrue('read_chunk_count', connection.writes.length === 3);
    checkTrue('read_chunk_addr', eq(connection.writes[1], [0x01, 30]));

    fram.maxChunk = 8;
    connection.writes.length = 0;
    await fram.write(0x0200, Buffer.alloc(20));
    checkTrue('custom_max_chunk', connection.writes.length === 3);
    let threw = false;
    try { fram.maxChunk = 0; } catch (e) { threw = e instanceof RangeError; }
    checkTrue('max_chunk_invalid', threw);
    fram.maxChunk = 30;

    await fram.fill(0x0300, 65, 0xAB);
    let all = true;
    for (let i = 0; i < 65; i++) if (connection.registers.get(0x0300 + i) !== 0xAB) all = false;
    checkTrue('fill', all);
    checkTrue('fill_end', (connection.registers.get(0x0300 + 65) || 0) === 0);
    await fram.fill(0x0300, 4);
    checkTrue('fill_default_zero', [0, 1, 2, 3].every(i => connection.registers.get(0x0300 + i) === 0));

    await fram.write(0x7FFE, [1, 2]);
    checkTrue('write_last_bytes', eq(await fram.read(0x7FFE, 2), [1, 2]));
    checkTrue('write_wrap_rejected', await rejects(() => fram.write(0x7FFF, [1, 2])));
    checkTrue('read_wrap_rejected', await rejects(() => fram.read(0x7FF0, 0x20)));
    checkTrue('fill_wrap_rejected', await rejects(() => fram.fill(0x7FFF, 2)));
    checkTrue('read_zero_length', (await fram.read(0, 0)).length === 0);

    const idc = new I2CConnectionMock();
    idc.setRegister(0xA6, [0x00, 0xA5, 0x10]);
    const id = await fram.readDeviceId(idc, 0x53);
    checkTrue('read_device_id', id.manufacturer === 0x00A && id.density === 0x5 && id.product === 0x10);
    checkTrue('read_device_id_word', eq(idc.writes[idc.writes.length - 1], [0xA6]));
    idc.setRegister(0xA0, [0x00, 0xA5, 0x10]);
    const id2 = await fram.readDeviceId(idc);
    checkTrue('read_device_id_default', id2.manufacturer === 0x00A && id2.product === 0x10);

    console.log('===DONE: ' + passed + ' passed, ' + failed + ' failed===');
    process.exit(failed === 0 ? 0 : 1);
}

main().catch(e => { console.error(e); process.exit(1); });
