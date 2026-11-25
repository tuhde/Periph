"use strict";
// Hardware test for the MX25L12845E: ids, erase / program / erase-aware write, block erases, BlockDevice,
// block protection and deep power-down. All erases and writes stay inside the last 64 KiB of the chip
// (0xFF0000..0xFFFFFF). Override the wiring with SPI_BUS, SPI_DEV.

const { SPIConnection } = require('../../packages/periph/src/connection/spi');
const { MX25L12845EFull } = require('../../packages/periph/src/chips/memory/mx25l12845e');
const { NotErased, OutOfRange, WriteProtected } = require('../../packages/periph/src/storage/block_device');

const SPI_BUS = parseInt(process.env.SPI_BUS || '0', 10);
const SPI_DEV = parseInt(process.env.SPI_DEV || '0', 10);
const SCRATCH = 0xFF0000;

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log(`FAIL ${label}`); failed++; }
}

async function rejects(fn, ErrType) {
    try { await fn(); } catch (e) { return e instanceof ErrType; }
    return false;
}

const hex = (b) => Buffer.from(b).toString('hex').match(/../g).join(' ');

(async () => {
    const connection = new SPIConnection(SPI_BUS, SPI_DEV, { mode: 0, maxSpeedHz: 8_000_000 });
    const flash = new MX25L12845EFull(connection);
    try {
        await flash.init();                                                     // Release deep power-down, check the JEDEC ID, () → Promise<void>
        checkTrue('init (JEDEC C2 20 18)', true);
    } catch (e) {
        checkTrue('init (JEDEC C2 20 18)', false);
        console.log('init error:', e.message);
        console.log(`===DONE: ${passed} passed, ${failed} failed===`);
        return;
    }
    checkTrue('jedec_id', (await flash.jedecId()).toString('hex') === 'c22018');
    checkTrue('electronic_id (RES) = 17h', (await flash.electronicId()) === 0x17);
    const [man, dev] = await flash.deviceIds();
    checkTrue('device_ids (REMS) = C2 17', man === 0xC2 && dev === 0x17);
    const sr = await flash.readStatus();
    checkTrue('status: not busy, WEL clear', (sr & 0x03) === 0);
    const bp = await flash.protection();
    console.log(`INFO status 0x${sr.toString(16)}, BP=${bp}`);
    console.log(`INFO security 0x${(await flash.readSecurity()).toString(16)} (bit0 = OTP factory locked)`);
    const esn = await flash.serialNumber();
    console.log('INFO ESN:', hex(esn));
    checkTrue('ESN stable', esn.equals(await flash.serialNumber()));
    checkTrue('SFDP signature', (await flash.readSfdp(0, 4)).toString('latin1') === 'SFDP');
    if (bp !== 0) {
        console.log(`INFO protection was BP=${bp}; clearing for the test`);
        await flash.setProtection(0);
    }

    await flash.eraseSector(SCRATCH);
    checkTrue('erase_sector', true);
    checkTrue('sector blank (all FF) after erase', (await flash.readBytes(SCRATCH, 4096)).every((b) => b === 0xFF));

    const page = Buffer.from(Array.from({ length: 256 }, (_, i) => (i * 3 + 1) & 0xFF));
    await flash.pageProgram(SCRATCH, page);
    checkTrue('page_program 256 bytes', true);
    checkTrue('read_bytes matches', (await flash.readBytes(SCRATCH, 256)).equals(page));
    checkTrue('fast_read matches', (await flash.fastRead(SCRATCH, 256)).equals(page));
    await flash.pageProgram(SCRATCH, Buffer.from([0x0F, 0xF0, 0x00, 0xFF]));
    const and = await flash.readBytes(SCRATCH, 4);
    checkTrue('programming only clears bits (AND)', and[0] === (page[0] & 0x0F) && and[1] === (page[1] & 0xF0) && and[2] === 0 && and[3] === page[3]);
    checkTrue('page-crossing program refused', await rejects(() => flash.pageProgram(SCRATCH + 250, page.subarray(0, 10)), OutOfRange));

    const data = Buffer.from(Array.from({ length: 3000 }, (_, i) => (i ^ (i >> 7)) & 0xFF));
    await flash.writeBytes(SCRATCH + 4096 - 100, data);
    checkTrue('write_bytes across two sectors', true);
    checkTrue('read back across sectors', (await flash.readBytes(SCRATCH + 4096 - 100, 3000)).equals(data));
    await flash.writeBytes(SCRATCH + 4096 - 100, data);
    checkTrue('identical rewrite', true);
    checkTrue('write past the end refused', await rejects(() => flash.writeBytes(flash.size() - 2, data.subarray(0, 5)), OutOfRange));

    await flash.eraseBlock32k(SCRATCH);
    checkTrue('erase_block_32k', true);
    await flash.eraseBlock64k(SCRATCH);
    checkTrue('erase_block_64k', true);
    checkTrue('64 KiB block blank', (await flash.readBytes(SCRATCH + 0xF000, 16)).every((b) => b === 0xFF));

    checkTrue('BlockDevice geometry', flash.blockSize() === 512 && flash.blockCount() === 32768 && flash.eraseSize() === 4096 && flash.needsErase());
    const blk = SCRATCH / 512;
    const w = Buffer.from(Array.from({ length: 1024 }, (_, i) => (i * 5 + 2) & 0xFF));
    await flash.write(blk, w);
    checkTrue('BlockDevice write 2 blocks', true);
    checkTrue('BlockDevice read back', (await flash.read(blk, 2)).equals(w));
    checkTrue('0->1 without erase -> NotErased', await rejects(() => flash.write(blk, Buffer.alloc(512, 0xFF)), NotErased));
    await flash.erase(blk, 8);
    checkTrue('BlockDevice erase (4 KiB)', true);
    checkTrue('unaligned erase refused', await rejects(() => flash.erase(blk + 1, 8), OutOfRange));
    await flash.sync();
    checkTrue('sync', true);

    await flash.setProtection(1);
    checkTrue('set_protection(1)', (await flash.protection()) === 1);
    checkTrue('program into protected area -> WriteProtected', await rejects(() => flash.pageProgram(SCRATCH + 0x8000, Buffer.from([0])), WriteProtected));
    checkTrue('erase of protected sector -> WriteProtected', await rejects(() => flash.eraseSector(SCRATCH + 0x8000), WriteProtected));
    await flash.setProtection(0);
    checkTrue('set_protection(0)', (await flash.protection()) === 0);
    await flash.pageProgram(SCRATCH + 0x8000, Buffer.from([0]));
    checkTrue('program after unprotect', true);

    await flash.deepPowerDown();
    checkTrue('deep_power_down', true);
    await new Promise((r) => setTimeout(r, 1));
    await flash.releaseDeepPowerDown();
    checkTrue('release_deep_power_down, RES again', (await flash.electronicId()) === 0x17);

    await flash.eraseBlock64k(SCRATCH);
    await flash.waitReady(1000);
    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
})();
