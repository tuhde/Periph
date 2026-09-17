'use strict';

const { SPIConnectionMock } = require('../../packages/periph/src/connection/spi_mock');
const { CanFrame, MCP2515Minimal, MCP2515Full } = require('../../packages/periph/src/chips/comms/mcp2515');

// SPI instruction bytes
const INSTR_RESET       = 0xC0;
const INSTR_READ        = 0x03;
const INSTR_WRITE       = 0x02;
const INSTR_LOAD_TX_BUF = 0x40;
const INSTR_RTS         = 0x80;
const INSTR_BIT_MODIFY  = 0x05;

// Register addresses
const REG_CANSTAT  = 0x0E;
const REG_CANCTRL  = 0x0F;
const REG_CNF3     = 0x28;
const REG_CNF2     = 0x29;
const REG_CNF1     = 0x2A;
const REG_CANINTE  = 0x2B;
const REG_EFLG     = 0x2D;
const REG_RXB0CTRL = 0x60;
const REG_RXB1CTRL = 0x70;
const REG_TXB0CTRL = 0x30;
const REG_TXB1CTRL = 0x40;
const REG_TXB2CTRL = 0x50;
const REG_RXM0SIDH = 0x20;

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

/**
 * Construct a fresh MCP2515Full on a mock SPI. The driver constructor
 * fires a fire-and-forget init sequence that needs CANSTAT to transition
 * from Configuration (0x80) to Normal (0x00) at the right moment; we
 * preset CANSTAT and let the writes accumulate, then advance CANSTAT
 * to Normal once CANCTRL has been set. Constructor awaits settle via
 * a couple of microtask flushes.
 */
async function newChip() {
    const connection = new SPIConnectionMock();
    connection.setRegister(REG_CANSTAT, [0x80]);
    connection.setRegister(REG_TXB0CTRL, [0x00]);
    connection.setRegister(REG_TXB1CTRL, [0x00]);
    connection.setRegister(REG_TXB2CTRL, [0x00]);
    const chip = new MCP2515Full(connection, 125);
    // Wait for the first 9 writes (RESET + 6 init reg writes + CANCTRL=Normal + first getMode in test setup)
    while (connection.writes.length < 9) {
        await new Promise(r => setImmediate(r));
    }
    connection.setRegister(REG_CANSTAT, [0x00]);
    // Drain the remaining microtasks from the second _waitOpMode.
    await new Promise(r => setImmediate(r));
    return { connection, chip };
}

async function main() {
    // --- CanFrame validation ---
    {
        const newFrame = new CanFrame(0x123, Buffer.from([1, 2, 3]), false, false);
        checkTrue('canframe_standard_ok',
            newFrame.id === 0x123 && newFrame.data.length === 3 && !newFrame.extended);
    }
    {
        const newFrame = new CanFrame(0x18FF1234, Buffer.alloc(8), true, false);
        checkTrue('canframe_extended_ok', newFrame.extended === true && newFrame.id === 0x18FF1234);
    }
    {
        let threw = false;
        try { new CanFrame(0x800, Buffer.alloc(0), false, false); }
        catch (e) { threw = e instanceof RangeError; }
        checkTrue('canframe_standard_oor_throws', threw);
    }
    {
        let threw = false;
        try { new CanFrame(0x20000000, Buffer.alloc(0), true, false); }
        catch (e) { threw = e instanceof RangeError; }
        checkTrue('canframe_extended_oor_throws', threw);
    }
    {
        let threw = false;
        try { new CanFrame(0x123, Buffer.alloc(9), false, false); }
        catch (e) { threw = e instanceof RangeError; }
        checkTrue('canframe_dlc_too_long_throws', threw);
    }

    // --- init sequence writes RESET, CNF1/2/3, RXB0CTRL, RXB1CTRL, CANINTE, TXBnCTRL, CANCTRL=Normal ---
    {
        const { connection } = await newChip();
        const all = connection.writes;
        checkTrue('init_first_is_reset', all[0].length === 1 && all[0][0] === INSTR_RESET);
        let sawCnf1 = false, sawCnf2 = false, sawCnf3 = false;
        let sawRxb0 = false, sawRxb1 = false;
        let sawCaninte = false;
        let sawTxb0 = false, sawTxb1 = false, sawTxb2 = false;
        let sawCanctrlNormal = false;
        for (const w of all) {
            if (w.length >= 3 && w[0] === INSTR_WRITE && w[1] === REG_CNF1) sawCnf1 = true;
            if (w.length >= 3 && w[0] === INSTR_WRITE && w[1] === REG_CNF2) sawCnf2 = true;
            if (w.length >= 3 && w[0] === INSTR_WRITE && w[1] === REG_CNF3) sawCnf3 = true;
            if (w.length >= 3 && w[0] === INSTR_WRITE && w[1] === REG_RXB0CTRL) sawRxb0 = true;
            if (w.length >= 3 && w[0] === INSTR_WRITE && w[1] === REG_RXB1CTRL) sawRxb1 = true;
            if (w.length >= 3 && w[0] === INSTR_WRITE && w[1] === REG_CANINTE) sawCaninte = true;
            if (w.length >= 3 && w[0] === INSTR_WRITE && w[1] === REG_TXB0CTRL) sawTxb0 = true;
            if (w.length >= 3 && w[0] === INSTR_WRITE && w[1] === REG_TXB1CTRL) sawTxb1 = true;
            if (w.length >= 3 && w[0] === INSTR_WRITE && w[1] === REG_TXB2CTRL) sawTxb2 = true;
            if (w.length >= 3 && w[0] === INSTR_WRITE && w[1] === REG_CANCTRL && w[2] === 0x00) sawCanctrlNormal = true;
        }
        checkTrue('init_writes_cnf1', sawCnf1);
        checkTrue('init_writes_cnf2', sawCnf2);
        checkTrue('init_writes_cnf3', sawCnf3);
        checkTrue('init_writes_rxb0ctrl', sawRxb0);
        checkTrue('init_writes_rxb1ctrl', sawRxb1);
        checkTrue('init_writes_caninte', sawCaninte);
        checkTrue('init_writes_txb0ctrl', sawTxb0);
        checkTrue('init_writes_txb1ctrl', sawTxb1);
        checkTrue('init_writes_txb2ctrl', sawTxb2);
        checkTrue('init_writes_canctrl_normal', sawCanctrlNormal);
        checkTrue('init_rxb0ctrl_is_accept_all_plus_bukt',
            connection.registers.get(REG_RXB0CTRL) === 0x64 /* 0x60 | 0x04 */);
        checkTrue('init_rxb1ctrl_is_accept_all',
            connection.registers.get(REG_RXB1CTRL) === 0x60);
        checkTrue('init_txb0ctrl_priority_3',
            connection.registers.get(REG_TXB0CTRL) === 0x03);
    }

    // --- send issues LOAD TX BUFFER + RTS ---
    {
        const { connection, chip } = await newChip();
        connection.setRegister(0, [0x00]);          // READ STATUS: no TX pending → TXB0 free
        connection.setRegister(REG_TXB0CTRL, [0x00]); // TXB0CTRL: TXREQ cleared
        await new Promise(r => setImmediate(r));
        const writesBefore = connection.writes.length;
        await chip.send(0x123, Buffer.from([1, 2, 3, 4]), false);
        const after = connection.writes.slice(writesBefore);
        // Match any LOAD TX BUFFER command (cmd 0x40..0x4F).
        const load = after.find(w => (w[0] & 0xE0) === 0x40);
        const rts  = after.find(w => w[0] === (INSTR_RTS | 0x01));
        checkTrue('send_issues_load_tx_buf', load !== undefined);
        checkTrue('send_issues_rts_txb0',    rts  !== undefined);
        checkTrue('send_load_buf0_offset',
            load && load[0] === (INSTR_LOAD_TX_BUF | 0) && load.length === 14);
    }

    // --- send extended frame sets EXIDE bit in SIDL ---
    {
        const { connection, chip } = await newChip();
        connection.setRegister(0, [0x00]);
        connection.setRegister(REG_TXB0CTRL, [0x00]);
        await new Promise(r => setImmediate(r));
        const writesBefore = connection.writes.length;
        await chip.send(0x1FFFFFFF, Buffer.from([0xAA]), true);
        // Match any LOAD TX BUFFER command (cmd 0x40..0x4F).
        const load = connection.writes.slice(writesBefore).find(w => (w[0] & 0xE0) === 0x40);
        // SIDL byte is at load[2]; EXIDE is bit 3 (0x08).
        checkTrue('send_extended_sets_exide',
            load !== undefined && (load[2] & 0x08) !== 0);
        // SIDH for extended = (id >> 21) & 0xFF; 0x1FFFFFFF >> 21 = 0xFF.
        checkTrue('send_extended_sidh', load && load[1] === 0xFF);
    }

    // --- sendBuffered writes to the specified buffer's offset ---
    {
        const { connection, chip } = await newChip();
        connection.setRegister(0, [0x00]);
        connection.setRegister(REG_TXB1CTRL, [0x00]);
        await new Promise(r => setImmediate(r));
        const writesBefore = connection.writes.length;
        await chip.sendBuffered(0x123, Buffer.from([1]), false, 1);
        const after = connection.writes.slice(writesBefore);
        // Match any LOAD TX BUFFER command (cmd 0x40..0x4F).
        const load = after.find(w => (w[0] & 0xE0) === 0x40);
        const rts  = after.find(w => w[0] === (INSTR_RTS | 0x02));
        // TXB1 uses LOAD TX BUFFER offset 2 (TXB1SIDH..D7): cmd = 0x40 | 2 = 0x42.
        checkTrue('send_buffered_uses_buf1_offset',
            load && load[0] === (INSTR_LOAD_TX_BUF | 2));
        checkTrue('send_buffered_rts_txb1', rts !== undefined);
    }

    // --- recv parses a standard CAN frame from RX0IF ---
    {
        const { connection, chip } = await newChip();
        // READ STATUS byte: set RX0IF (bit 0) = 0x01.
        connection.setRegister(0, [0x01]);
        // RX buffer data starts at 0x61 in the mock (RXB0SIDH..RXB0D7).
        // SIDH=0x12, SIDL=0x60 (SID[2:0]=0b011,EXIDE=0; id=(0x12<<3)|(0x60>>5)=0x93),
        // DLC=0x04, data=0xDE,0xAD,0xBE,0xEF.
        connection.setRegister(0x61, [0x12, 0x60, 0x00, 0x00, 0x04, 0xDE, 0xAD, 0xBE, 0xEF]);
        await new Promise(r => setImmediate(r));
        const frame = await chip.recv(100);
        checkTrue('recv_returns_frame', frame !== null);
        checkTrue('recv_standard_id',
            frame && frame.id === 0x93 && frame.extended === false && frame.rtr === false);
        checkTrue('recv_dlc_4', frame && frame.data.length === 4);
        checkTrue('recv_data_bytes',
            frame && frame.data[0] === 0xDE && frame.data[1] === 0xAD &&
            frame.data[2] === 0xBE && frame.data[3] === 0xEF);
    }

    // --- recv with no frame returns null ---
    {
        const { connection, chip } = await newChip();
        connection.setRegister(0, [0x00]);   // no RX bits set
        await new Promise(r => setImmediate(r));
        const frame = await chip.recv(0);
        checkTrue('recv_none_returns_null', frame === null);
    }

    // --- set_filter writes to filter base ---
    {
        const { connection, chip } = await newChip();
        // Pretend we're in CONFIG mode for the duration of the call.
        // CANSTAT=0x80 (CONFIG) so getMode returns 'config' and the driver
        // skips the mode-switch dance.
        connection.setRegister(REG_CANCTRL, [0x80]);
        connection.setRegister(REG_CANSTAT, [0x80]);
        await new Promise(r => setImmediate(r));
        const writesBefore = connection.writes.length;
        await chip.setFilter(2, 0x100, false);
        const after = connection.writes.slice(writesBefore);
        // Filter 2 base is 0x08 (RXF2SIDH); expect WRITE to 0x08 with SIDH value.
        checkTrue('set_filter_writes_filter_base',
            after.some(w => w.length >= 3 && w[0] === INSTR_WRITE && w[1] === 0x08));
        const writesFilter = after.filter(w => w.length >= 3 && w[0] === INSTR_WRITE && w[1] === 0x08);
        // SIDH for 0x100 standard = 0x100 >> 3 = 0x20
        checkTrue('set_filter_sidh_value',
            writesFilter[0] && writesFilter[0][2] === 0x20);
    }

    // --- set_mask writes to RXM0SIDH ---
    {
        const { connection, chip } = await newChip();
        // Pretend we're in CONFIG mode for the duration of the call.
        connection.setRegister(REG_CANCTRL, [0x80]);
        connection.setRegister(REG_CANSTAT, [0x80]);
        await new Promise(r => setImmediate(r));
        const writesBefore = connection.writes.length;
        await chip.setMask(0, 0x7FF, false);
        const after = connection.writes.slice(writesBefore);
        checkTrue('set_mask_writes_rxm0sidh',
            after.some(w => w.length >= 3 && w[0] === INSTR_WRITE && w[1] === REG_RXM0SIDH));
        const writesMask = after.filter(w => w.length >= 3 && w[0] === INSTR_WRITE && w[1] === REG_RXM0SIDH);
        // SIDH for 0x7FF standard = 0x7FF >> 3 = 0xFF
        checkTrue('set_mask_sidh_value',
            writesMask[0] && writesMask[0][2] === 0xFF);
    }

    // --- set_rx_mode issues BIT MODIFY on RXBnCTRL ---
    {
        const { connection, chip } = await newChip();
        await new Promise(r => setImmediate(r));
        const writesBefore = connection.writes.length;
        await chip.setRxMode(0, 0);
        const after = connection.writes.slice(writesBefore);
        const bm = after.find(w => w[0] === INSTR_BIT_MODIFY);
        checkTrue('set_rx_mode_uses_bit_modify', bm !== undefined);
        checkTrue('set_rx_mode_bm_address_rxb0ctrl',
            bm && bm[1] === REG_RXB0CTRL);
        checkTrue('set_rx_mode_bm_mask_value',
            bm && bm[2] === 0x60 && bm[3] === 0x00);
    }

    // --- set_one_shot toggles OSM bit in CANCTRL ---
    {
        const { connection, chip } = await newChip();
        await new Promise(r => setImmediate(r));
        const writesBefore = connection.writes.length;
        await chip.setOneShot(true);
        let bm = connection.writes.slice(writesBefore).find(w => w[0] === INSTR_BIT_MODIFY);
        checkTrue('set_one_shot_true_sets_osm',
            bm && bm[1] === REG_CANCTRL && bm[2] === 0x08 && bm[3] === 0x08);
        const writesBefore2 = connection.writes.length;
        await chip.setOneShot(false);
        bm = connection.writes.slice(writesBefore2).find(w => w[0] === INSTR_BIT_MODIFY);
        checkTrue('set_one_shot_false_clears_osm',
            bm && bm[1] === REG_CANCTRL && bm[2] === 0x08 && bm[3] === 0x00);
    }

    // --- clear_overflow issues BIT MODIFY to EFLG ---
    {
        const { connection, chip } = await newChip();
        await new Promise(r => setImmediate(r));
        const writesBefore = connection.writes.length;
        await chip.clearOverflow(0);
        const bm = connection.writes.slice(writesBefore).find(w => w[0] === INSTR_BIT_MODIFY);
        checkTrue('clear_overflow_bm_to_eflg_buf0', bm !== undefined);
        checkTrue('clear_overflow_buf0_clears_rx0ovr',
            bm && bm[1] === REG_EFLG && bm[2] === 0x40 && bm[3] === 0x00);
        const writesBefore2 = connection.writes.length;
        await chip.clearOverflow(1);
        const bm2 = connection.writes.slice(writesBefore2).find(w => w[0] === INSTR_BIT_MODIFY);
        checkTrue('clear_overflow_buf1_clears_rx1ovr',
            bm2 && bm2[1] === REG_EFLG && bm2[2] === 0x80 && bm2[3] === 0x00);
    }

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
