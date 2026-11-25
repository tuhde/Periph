"use strict";
// Hardware test for the SD card (SPI mode): init, CID/OCR, single and multi-block round-trips in the last
// 8 blocks of the card (contents saved and restored). CS is a GPIO (spi-device noChipSelect); override the
// wiring with SPI_BUS, SPI_DEV, GPIOCHIP, CS_LINE.

const { Default } = require('opengpio');
const { SPIConnection } = require('../../packages/periph/src/connection/spi');
const { GpioOutputPin } = require('../../packages/periph/src/connection/output_pin');
const { SDSPIFull } = require('../../packages/periph/src/chips/memory/sd_spi');
const { OutOfRange } = require('../../packages/periph/src/storage/block_device');

const SPI_BUS = parseInt(process.env.SPI_BUS || '0', 10);
const SPI_DEV = parseInt(process.env.SPI_DEV || '0', 10);
const GPIOCHIP = process.env.GPIOCHIP || '/dev/gpiochip0';
const CS_LINE = parseInt(process.env.CS_LINE || '25', 10);

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

(async () => {
    const connection = new SPIConnection(SPI_BUS, SPI_DEV, {
        mode: 0, maxSpeedHz: 400_000,
        csPin: new GpioOutputPin(Default.output({ chip: GPIOCHIP, line: CS_LINE })),
    });
    const sd = new SDSPIFull(connection);
    try {
        await sd.init();                                                       // Run the SPI-mode init sequence, () → Promise<void>
        checkTrue('init', true);
    } catch (e) {
        checkTrue('init', false);
        console.log('init error:', e.message);
        console.log(`===DONE: ${passed} passed, ${failed} failed===`);
        return;
    }
    const info = sd.info();
    console.log(`type=${sd.cardType()} blocks=${info.blockCount} name=${info.name}`);
    checkTrue('card_type_detected', sd.cardType() !== 0);
    checkTrue('geometry', info.blockCount > 0 && info.blockSize === 512);
    checkTrue('no_erase_needed', sd.eraseSize() === 512 && !sd.needsErase());
    checkTrue('is_present', await sd.isPresent());
    checkTrue('cid_read', (await sd.cid()).length === 16);
    checkTrue('ocr_has_3v3_window', ((await sd.ocr()) & 0x00FF8000) !== 0);

    // Scratch area: the last 8 blocks, contents saved and restored.
    const scratch = sd.blockCount() - 8;
    const saved = await sd.read(scratch, 8);
    checkTrue('save_scratch', saved.length === 8 * 512);
    const pat = Buffer.from(Array.from({ length: 8 * 512 }, (_, i) => (i * 7 + 3) & 0xFF));

    await sd.write(scratch, pat.subarray(0, 512));
    checkTrue('single_roundtrip', (await sd.read(scratch, 1)).equals(pat.subarray(0, 512)));
    await sd.write(scratch, pat);
    await sd.sync();
    checkTrue('multi_roundtrip', (await sd.read(scratch, 8)).equals(pat));
    checkTrue('out_of_range', await rejects(() => sd.read(sd.blockCount(), 1), OutOfRange));

    await sd.setCrc(false);
    checkTrue('read_with_crc_off', (await sd.read(scratch, 1)).equals(pat.subarray(0, 512)));
    await sd.setCrc(true);
    checkTrue('set_speed_8mhz', await sd.setSpeed(8_000_000));
    checkTrue('read_at_higher_speed', (await sd.read(scratch, 8)).equals(pat));

    await sd.write(scratch, saved);
    await sd.sync();
    checkTrue('restore_scratch', (await sd.read(scratch, 8)).equals(saved));

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    connection.close();
})();
