'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { AT24C02NMinimal, AT24C02NFull } = require('../../packages/periph/src/chips/memory/at24c02n');

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

async function main() {
    const connection = new I2CConnectionMock();
        const ee = new AT24C02NFull(connection);
    checkTrue('init_no_traffic', connection.writes.length === 0);
    checkTrue('size', ee.size() === 256);
    checkTrue('page_size', ee.pageSize() === 8);

    connection.setRegister(0x0034, [0x42]);
    checkTrue('read_byte', (await ee.readByte(0x0034)) === 0x42);
    checkTrue('read_byte_address_bytes', eq(connection.writes[connection.writes.length - 1], [0x12, 0x34]));

    connection.writes.length = 0;
    await ee.writeByte(0x0010, 0x99);
    checkTrue('write_byte', connection.registers.get(0x0010) === 0x99);
    checkTrue('write_byte_frame', eq(connection.writes[0], [0x00, 0x10, 0x99]));
    checkTrue('write_byte_ack_poll', eq(connection.writes[1], [0x00, 0x00]));

    checkTrue('minimal_class', new AT24C02NMinimal(connection) instanceof AT24C02NMinimal);
    checkTrue('read_byte_range_hi', await rejects(() => ee.readByte(0x0100)));
    checkTrue('read_byte_range_neg', await rejects(() => ee.readByte(-1)));
    checkTrue('write_byte_range', await rejects(() => ee.writeByte(0x0100, 1)));

    // ACK polling: two NACKs, then the chip answers.
    connection.failNextWrites(2);
    connection.writes.length = 0;
    checkTrue('wait_ready_after_nacks', await ee.waitReady(50));
    checkTrue('wait_ready_polled_three_times', connection.writes.length === 3);
    connection.failNextWrites(1000);
    checkTrue('wait_ready_timeout', !(await ee.waitReady(5)));
    connection.failNextWrites(0);
    connection.failNextWrites(1000, 1);
    checkTrue('write_byte_timeout_raises', await rejects(() => ee.writeByte(0x0021, 2), Error));
    connection.failNextWrites(0);

    // Page splitting: 70 bytes from 0x0070 (boundary at 0x0080, maxChunk 30).
    checkTrue('max_chunk_default', ee.maxChunk === 30);
    const data = Buffer.from(Array.from({ length: 70 }, (_, i) => i));
    connection.writes.length = 0;
    await ee.write(0x0070, data);
    const frames = connection.writes.filter((w) => w.length > 2);
    checkTrue('write_first_chunk_stops_at_page', eq(frames[0], Buffer.concat([Buffer.from([0x00, 0x70]), data.subarray(0, 16)])));
    checkTrue('write_second_chunk_page_start', eq(frames[1], Buffer.concat([Buffer.from([0x00, 0x80]), data.subarray(16, 46)])));
    checkTrue('write_third_chunk_stops_at_page', eq(frames[2], Buffer.concat([Buffer.from([0x00, 0x9E]), data.subarray(46, 48)])));
    checkTrue('write_fourth_chunk', eq(frames[3], Buffer.concat([Buffer.from([0x00, 0xA0]), data.subarray(48)])));
    checkTrue('write_ack_polled_per_chunk', connection.writes.length === 2 * frames.length);
    checkTrue('write_roundtrip', eq(await ee.read(0x0070, 70), data));

    ee.maxChunk = 8;
    connection.writes.length = 0;
    await ee.write(0x00E0, Buffer.alloc(20));
    checkTrue('custom_max_chunk', connection.writes.filter((w) => w.length > 2).length === 3);
    let threw = false;
    try { ee.maxChunk = 0; } catch (e) { threw = e instanceof RangeError; }
    checkTrue('max_chunk_invalid', threw);
    ee.maxChunk = 30;

    // writePage.
    connection.writes.length = 0;
    const page = Buffer.from(Array.from({ length: 8 }, (_, i) => i));
    await ee.writePage(0x00C0, page);
    checkTrue('write_page_full', eq(connection.writes[0], Buffer.concat([Buffer.from([0x03, 0x00]), page])));
    checkTrue('write_page_cross_rejected', await rejects(() => ee.writePage(0x00CC, Buffer.alloc(8))));
    checkTrue('write_page_too_long', await rejects(() => ee.writePage(0x00D0, Buffer.alloc(9))));
    checkTrue('write_page_range', await rejects(() => ee.writePage(0x00FF, Buffer.alloc(2))));

    await ee.fill(0x0040, 100, 0xAB);
    let all = true;
    for (let i = 0; i < 100; i++) if (connection.registers.get(0x0040 + i) !== 0xAB) all = false;
    checkTrue('fill', all);
    checkTrue('fill_end', (connection.registers.get(0x0040 + 100) || 0) === 0);
    await ee.fill(0x0040, 4);
    checkTrue('fill_default_zero', [0, 1, 2, 3].every(i => connection.registers.get(0x0040 + i) === 0));

    await ee.write(0x00FE, [1, 2]);
    checkTrue('write_last_bytes', eq(await ee.read(0x00FE, 2), [1, 2]));
    checkTrue('write_wrap_rejected', await rejects(() => ee.write(0x00FF, [1, 2])));
    checkTrue('read_wrap_rejected', await rejects(() => ee.read(0x00F0, 0x20)));
    checkTrue('fill_wrap_rejected', await rejects(() => ee.fill(0x00FF, 2)));
    checkTrue('read_zero_length', (await ee.read(0, 0)).length === 0);

    console.log('===DONE: ' + passed + ' passed, ' + failed + ' failed===');
    process.exit(failed === 0 ? 0 : 1);
}

main().catch(e => { console.error(e); process.exit(1); });
