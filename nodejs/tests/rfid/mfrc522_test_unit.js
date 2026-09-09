'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { MFRC522Full } = require('../../packages/periph/src/chips/rfid/mfrc522');

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

function flushMicrotasks() {
    return new Promise((resolve) => setImmediate(resolve));
}

// --- register addresses (module-private in the driver; mirrored here) ------
// SPI addressing: write = (reg<<1)&0x7E, read = write|0x80.
const _REG_COMMAND      = 0x01;
const _REG_COM_IRQ      = 0x04;
const _REG_DIV_IRQ      = 0x05;
const _REG_ERROR        = 0x06;
const _REG_STATUS_2     = 0x08;
const _REG_FIFO_DATA    = 0x09;
const _REG_FIFO_LEVEL   = 0x0A;
const _REG_MODE         = 0x11;
const _REG_TX_MODE      = 0x12;
const _REG_RX_MODE      = 0x13;
const _REG_TX_CONTROL   = 0x14;
const _REG_TX_ASK       = 0x15;
const _REG_T_MODE       = 0x2A;
const _REG_T_PRESCALER  = 0x2B;
const _REG_T_RELOAD_H   = 0x2C;
const _REG_T_RELOAD_L   = 0x2D;
const _REG_RF_CFG       = 0x26;
const _REG_AUTO_TEST    = 0x36;
const _REG_VERSION      = 0x37;
const _REG_CRC_RESULT_H = 0x21;
const _REG_CRC_RESULT_L = 0x22;

function waddr(reg) { return (reg << 1) & 0x7E; }
function raddr(reg) { return waddr(reg) | 0x80; }

/**
 * I2CConnectionMock plus a FIFO: MFRC522 reads FIFO_LEVEL then FIFO_DATA one
 * byte at a time via writeRead(), which the base register-map mock can't
 * model on its own (a plain register always returns the same fixed byte, but
 * FIFO_LEVEL/FIFO_DATA must reflect "how many bytes are left in *this*
 * response" across several transceive rounds in one call, e.g. readUid()'s
 * REQA -> anticollision -> select -> halt sequence).
 *
 * queueFifo(chunk) queues one whole response as a unit. FIFO_LEVEL reads
 * report the current front chunk's remaining length; FIFO_DATA reads pop one
 * byte from it, and the chunk is dropped once drained so the next queued
 * response becomes visible to the next FIFO_LEVEL read.
 */
class FifoAwareMock extends I2CConnectionMock {
    constructor() {
        super();
        this._fifoChunks = [];
    }

    queueFifo(data) {
        this._fifoChunks.push(Array.from(data));
    }

    async writeRead(data, n) {
        const buf = Buffer.from(data);
        const addr = buf[0];
        if (addr === raddr(_REG_FIFO_LEVEL) && n === 1) {
            this.writes.push(buf);
            const level = this._fifoChunks.length ? this._fifoChunks[0].length : 0;
            return Buffer.from([level]);
        }
        if (addr === raddr(_REG_FIFO_DATA) && n === 1) {
            this.writes.push(buf);
            if (!this._fifoChunks.length) return Buffer.from([0]);
            const byte = this._fifoChunks[0].shift();
            if (this._fifoChunks[0].length === 0) this._fifoChunks.shift();
            return Buffer.from([byte]);
        }
        return super.writeRead(data, n);
    }
}

function writesAt(conn, addr) {
    return conn.writes.filter((w) => w.length === 2 && w[0] === addr);
}

function prepTransceive(conn, { comIrq = 0x30, error = 0x00, fifoBytes = null } = {}) {
    conn.setRegister(raddr(_REG_COM_IRQ), [comIrq]);
    conn.setRegister(raddr(_REG_ERROR), [error]);
    if (fifoBytes !== null) conn.queueFifo(fifoBytes);
}

async function main() {
    // --- constructor / init sequence ----------------------------------------
    const connection = new FifoAwareMock();
    const sensor = new MFRC522Full(connection);
    await flushMicrotasks(); // let the fire-and-forget _initChip() finish before asserting on it
    checkTrue('init', true);

    checkTrue('init_soft_reset', writesAt(connection, waddr(_REG_COMMAND))[0][1] === 0x0F);
    checkTrue('init_timer_mode', writesAt(connection, waddr(_REG_T_MODE)).slice(-1)[0][1] === 0x80);
    checkTrue('init_timer_prescaler', writesAt(connection, waddr(_REG_T_PRESCALER)).slice(-1)[0][1] === 0xA9);
    checkTrue('init_timer_reload_h', writesAt(connection, waddr(_REG_T_RELOAD_H)).slice(-1)[0][1] === 0x03);
    checkTrue('init_timer_reload_l', writesAt(connection, waddr(_REG_T_RELOAD_L)).slice(-1)[0][1] === 0xE8);
    checkTrue('init_force_100_ask', writesAt(connection, waddr(_REG_TX_ASK)).slice(-1)[0][1] === 0x40);
    checkTrue('init_mode_crc_a', writesAt(connection, waddr(_REG_MODE)).slice(-1)[0][1] === 0x3D);
    checkTrue('init_antenna_on', (connection.registers.get(waddr(_REG_TX_CONTROL)) & 0x03) === 0x03);

    // --- isCardPresent(): REQA -> 2-byte ATQA -------------------------------
    const connection2 = new FifoAwareMock();
    const sensor2 = new MFRC522Full(connection2);
    await flushMicrotasks();
    prepTransceive(connection2, { fifoBytes: [0x04, 0x00] });
    checkTrue('is_card_present_true', (await sensor2.isCardPresent()) === true);

    const connection3 = new FifoAwareMock();
    const sensor3 = new MFRC522Full(connection3);
    await flushMicrotasks();
    prepTransceive(connection3, { comIrq: 0x01 }); // TimerIRq only -> no card
    checkTrue('is_card_present_false', (await sensor3.isCardPresent()) === false);

    // --- readUid(): single cascade level (4-byte UID) -----------------------
    const connection4 = new FifoAwareMock();
    const sensor4 = new MFRC522Full(connection4);
    await flushMicrotasks();
    const uidBytes = [0x12, 0x34, 0x56, 0x78];
    let bcc = 0;
    for (const b of uidBytes) bcc ^= b;

    connection4.setRegister(raddr(_REG_COM_IRQ), [0x30]);
    connection4.setRegister(raddr(_REG_ERROR), [0x00]);
    // Every _calcCrc() call in this flow (inside _select() and _haltCard())
    // polls DIV_IRQ, which defaults to 0 (never set here) - it just runs its
    // full bounded retry loop and returns a placeholder CRC, which is fine:
    // the mock's transceive success is keyed on COM_IRQ/ERROR, not on the CRC
    // bytes actually being cryptographically correct.
    // REQA response (isCardPresent(), called first by readUid())
    connection4.queueFifo([0x04, 0x00]);
    // Anticollision CL1 response: 4 UID bytes + BCC
    connection4.queueFifo([...uidBytes, bcc]);
    // Select CL1 response: SAK with completion bit clear (0x00 = complete, single-size UID)
    connection4.queueFifo([0x00]);
    // HLTA (halt) - result ignored by the driver, no response bytes needed

    const uid = await sensor4.readUid();
    checkTrue('read_uid', uid !== null && uid.equals(Buffer.from(uidBytes)));

    const connection5 = new FifoAwareMock();
    const sensor5 = new MFRC522Full(connection5);
    await flushMicrotasks();
    connection5.setRegister(raddr(_REG_COM_IRQ), [0x01]); // TimerIRq only -> no card
    checkTrue('read_uid_none', (await sensor5.readUid()) === null);

    // --- antenna control ------------------------------------------------------
    const connection6 = new FifoAwareMock();
    const sensor6 = new MFRC522Full(connection6);
    await flushMicrotasks();
    await sensor6.antennaOff();
    checkTrue('antenna_off', (connection6.registers.get(waddr(_REG_TX_CONTROL)) & 0x03) === 0x00);
    await sensor6.antennaOn();
    checkTrue('antenna_on', (connection6.registers.get(waddr(_REG_TX_CONTROL)) & 0x03) === 0x03);

    await sensor6.setAntennaGain(38);
    checkTrue('set_antenna_gain', (connection6.registers.get(waddr(_REG_RF_CFG)) & 0x70) === 0x50);
    connection6.setRegister(raddr(_REG_RF_CFG), [0x60]);
    checkTrue('antenna_gain', (await sensor6.antennaGain()) === 43);

    let raisedInvalidGain = false;
    try {
        await sensor6.setAntennaGain(99);
    } catch (e) {
        raisedInvalidGain = e instanceof RangeError;
    }
    checkTrue('set_antenna_gain_invalid_throws', raisedInvalidGain);

    // --- version() -------------------------------------------------------------
    const connection7 = new FifoAwareMock();
    const sensor7 = new MFRC522Full(connection7);
    await flushMicrotasks();
    connection7.setRegister(raddr(_REG_VERSION), [0x92]); // chipType=9, version=2
    const ver = await sensor7.version();
    checkTrue('version', ver.chipType === 9 && ver.version === 2);

    // --- selfTest(): FIFO fills to >=64 bytes on the first CalcCRC iteration, --
    // then readFifo(64) must return the exact v1.0 reference table.
    const connection8 = new FifoAwareMock();
    const sensor8 = new MFRC522Full(connection8);
    await flushMicrotasks();
    connection8.setRegister(raddr(_REG_VERSION), [0x91]); // version=1 -> v1.0 reference table
    const refV10 = [
        0x00, 0x87, 0x98, 0x0F, 0x49, 0xFF, 0x07, 0x19,
        0xBF, 0x22, 0x30, 0x49, 0x59, 0x63, 0xAD, 0xCA,
        0x7F, 0xE3, 0x4E, 0x03, 0x5C, 0x4E, 0x49, 0x50,
        0x47, 0x9A, 0x37, 0x61, 0xE7, 0xE2, 0xC6, 0x2E,
        0x75, 0x5A, 0xED, 0x04, 0x3D, 0x02, 0x4B, 0x78,
        0x32, 0xFF, 0x58, 0x3B, 0x7C, 0xE9, 0x00, 0x94,
        0xB4, 0x4A, 0x59, 0x5B, 0xFD, 0xC9, 0x29, 0xDF,
        0x35, 0x96, 0x98, 0x9E, 0x4F, 0x30, 0x32, 0x8D,
    ];
    connection8.queueFifo(refV10);
    checkTrue('self_test_pass', (await sensor8.selfTest()) === true);

    // --- authenticate() / stopCrypto() ------------------------------------------
    const connection9 = new FifoAwareMock();
    const sensor9 = new MFRC522Full(connection9);
    await flushMicrotasks();
    connection9.setRegister(raddr(_REG_STATUS_2), [0x08]); // MFCrypto1On set immediately
    const ok = await sensor9.authenticate(4, MFRC522Full.KEY_A, Buffer.from([0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF]), Buffer.from(uidBytes));
    checkTrue('authenticate', ok === true);

    await sensor9.stopCrypto();
    checkTrue('stop_crypto', (connection9.registers.get(waddr(_REG_STATUS_2)) & 0x08) === 0x00);

    const connection9b = new FifoAwareMock();
    const sensor9b = new MFRC522Full(connection9b);
    await flushMicrotasks();
    checkTrue('authenticate_bad_key_length',
        (await sensor9b.authenticate(4, MFRC522Full.KEY_A, Buffer.from([0xFF, 0xFF, 0xFF, 0xFF, 0xFF]), Buffer.from(uidBytes))) === false);

    // --- readBlock() / writeBlock() (via the CRC + transceive mocked flow) ----
    const connection10 = new FifoAwareMock();
    const sensor10 = new MFRC522Full(connection10);
    await flushMicrotasks();
    const blockData = Buffer.from(Array.from({ length: 16 }, (_, i) => i));
    // _calcCrc() polls DIV_IRQ; make it show CRCIRq set immediately, and preload
    // CRC_RESULT_H/L (module-private regs 0x21/0x22) with a fixed placeholder -
    // the driver just forwards whatever the chip returns as the trailing 2
    // command bytes, so any placeholder value round-trips correctly.
    const REG_DIV_IRQ_R = raddr(_REG_DIV_IRQ);
    const REG_CRC_H_R = raddr(_REG_CRC_RESULT_H);
    const REG_CRC_L_R = raddr(_REG_CRC_RESULT_L);
    connection10.setRegister(REG_DIV_IRQ_R, [0x04]);
    connection10.setRegister(REG_CRC_H_R, [0xAB]);
    connection10.setRegister(REG_CRC_L_R, [0xCD]);
    connection10.setRegister(raddr(_REG_COM_IRQ), [0x30]);
    connection10.setRegister(raddr(_REG_ERROR), [0x00]);
    connection10.queueFifo(Array.from(blockData));
    const got = await sensor10.readBlock(4);
    checkTrue('read_block', got !== null && got.equals(blockData));

    const connection11 = new FifoAwareMock();
    const sensor11 = new MFRC522Full(connection11);
    await flushMicrotasks();
    connection11.setRegister(REG_DIV_IRQ_R, [0x04]);
    connection11.setRegister(REG_CRC_H_R, [0xAB]);
    connection11.setRegister(REG_CRC_L_R, [0xCD]);
    connection11.setRegister(raddr(_REG_COM_IRQ), [0x30]);
    connection11.setRegister(raddr(_REG_ERROR), [0x00]);
    connection11.queueFifo([0x0A]); // phase 1 ACK (0x0A in low nibble)
    connection11.queueFifo([0x0A]); // phase 2 ACK
    checkTrue('write_block', (await sensor11.writeBlock(4, blockData)) === true);

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
