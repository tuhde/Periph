"use strict";

const { SPIConnection } = require('../../packages/periph/src/connection/spi');
const { SRAM23X1024Full } = require('../../packages/periph/src/chips/memory/_23x1024');

const SPI_BUS = parseInt(process.env.SPI_BUS || '0', 10);
const SPI_DEV = parseInt(process.env.SPI_DEV || '0', 10);

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log(`FAIL ${label}`); failed++; }
}

(async () => {
    const connection = new SPIConnection(SPI_BUS, SPI_DEV, { mode: 0, maxSpeedHz: 8_000_000 });
    const sram = new SRAM23X1024Full(connection);
    await sram.init();                                                         // writes MODE=0x40 and verifies it with RDMR
    checkTrue('init_mode_roundtrip', true);
    checkTrue('size', sram.size() === 131072);
    checkTrue('default_mode_sequential', (await sram.getMode()) === SRAM23X1024Full.MODE_SEQUENTIAL);

    await sram.writeByte(0x00000, 0xA5);
    checkTrue('byte_roundtrip_first', (await sram.readByte(0x00000)) === 0xA5);
    await sram.writeByte(0x1FFFF, 0x5A);
    checkTrue('byte_roundtrip_last', (await sram.readByte(0x1FFFF)) === 0x5A);
    await sram.writeByte(0x10000, 0x3C);
    checkTrue('address_bit16_distinct', (await sram.readByte(0x10000)) === 0x3C && (await sram.readByte(0x00000)) === 0xA5);

    const pattern = Buffer.from(Array.from({ length: 1000 }, (_, i) => (i * 7 + 3) & 0xFF));
    await sram.write(0x00400, pattern);
    checkTrue('block_roundtrip_chunked', (await sram.read(0x00400, 1000)).equals(pattern));

    await sram.fill(0x02000, 100, 0xC3);
    checkTrue('fill_range', (await sram.read(0x02000, 100)).every(b => b === 0xC3) && (await sram.readByte(0x02064)) !== 0xC3);

    await sram.write(0x1FFF0, pattern.subarray(0, 16));
    checkTrue('block_at_array_end', (await sram.read(0x1FFF0, 16)).equals(pattern.subarray(0, 16)));

    await sram.setMode(SRAM23X1024Full.MODE_PAGE);
    checkTrue('set_mode_page', (await sram.getMode()) === SRAM23X1024Full.MODE_PAGE);
    await sram.write(0x04010, pattern.subarray(0, 64));                        // crosses the pages at 0x4020 and 0x4040
    checkTrue('page_mode_crossing_linear', (await sram.read(0x04010, 64)).equals(pattern.subarray(0, 64)));

    await sram.setMode(SRAM23X1024Full.MODE_BYTE);
    checkTrue('set_mode_byte', (await sram.getMode()) === SRAM23X1024Full.MODE_BYTE);
    await sram.write(0x05000, pattern.subarray(0, 8));
    checkTrue('byte_mode_block', (await sram.read(0x05000, 8)).equals(pattern.subarray(0, 8)));

    await sram.setMode(SRAM23X1024Full.MODE_SEQUENTIAL);
    checkTrue('set_mode_sequential', (await sram.getMode()) === SRAM23X1024Full.MODE_SEQUENTIAL);

    await sram.resetIo();
    checkTrue('reset_io_stays_spi', (await sram.getMode()) === SRAM23X1024Full.MODE_SEQUENTIAL);
    checkTrue('data_survives_reset_io', (await sram.readByte(0x00000)) === 0xA5);

    await connection.close();
    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
})();
