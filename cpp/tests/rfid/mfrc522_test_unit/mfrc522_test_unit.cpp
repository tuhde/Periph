#include <stdio.h>
#include <vector>
#include <deque>
#include "I2CConnectionMock.h"
#include "MFRC522.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

// Access protected register/command constants for building expected values.
class MFRC522TestAccess : public MFRC522Full {
public:
    using MFRC522Full::MFRC522Full;
    using MFRC522Full::REG_COMMAND;
    using MFRC522Full::REG_COM_IRQ;
    using MFRC522Full::REG_DIV_IRQ;
    using MFRC522Full::REG_ERROR;
    using MFRC522Full::REG_STATUS_2;
    using MFRC522Full::REG_FIFO_DATA;
    using MFRC522Full::REG_FIFO_LEVEL;
    using MFRC522Full::REG_MODE;
    using MFRC522Full::REG_TX_MODE;
    using MFRC522Full::REG_RX_MODE;
    using MFRC522Full::REG_TX_CONTROL;
    using MFRC522Full::REG_TX_ASK;
    using MFRC522Full::REG_T_MODE;
    using MFRC522Full::REG_T_PRESCALER;
    using MFRC522Full::REG_T_RELOAD_H;
    using MFRC522Full::REG_T_RELOAD_L;
    using MFRC522Full::REG_RF_CFG;
    using MFRC522Full::REG_VERSION;
    using MFRC522Full::REG_CRC_RESULT_H;
    using MFRC522Full::REG_CRC_RESULT_L;
};

// SPI addressing (default bus): write = (reg<<1)&0x7E, read = write|0x80.
static uint8_t waddr(uint8_t reg) { return (reg << 1) & 0x7E; }
static uint8_t raddr(uint8_t reg) { return waddr(reg) | 0x80; }

// I2CConnectionMock plus a FIFO: MFRC522 reads FIFO_LEVEL then FIFO_DATA one
// byte at a time via write_read(), which the base register-map mock can't
// model on its own (a plain register always returns the same fixed byte, but
// FIFO_LEVEL/FIFO_DATA must reflect "how many bytes are left in *this*
// response" across several transceive rounds in one call, e.g. read_uid()'s
// REQA -> anticollision -> select -> halt sequence).
//
// queueFifo(chunk) queues one whole response as a unit. A FIFO_LEVEL read
// reports the current front chunk's remaining length; a FIFO_DATA read pops
// one byte from it, and the chunk is dropped once drained so the next queued
// response becomes visible to the next FIFO_LEVEL read.
class FifoAwareMock : public I2CConnectionMock {
public:
    void queueFifo(const std::vector<uint8_t>& data) {
        _fifoChunks.push_back(data);
    }

protected:
    void _write_read(const uint8_t* data, size_t data_len,
                      uint8_t* buf, size_t buf_len) override {
        uint8_t addr = data[0];
        if (addr == raddr(MFRC522TestAccess::REG_FIFO_LEVEL) && buf_len == 1) {
            buf[0] = _fifoChunks.empty() ? 0 : static_cast<uint8_t>(_fifoChunks.front().size());
            return;
        }
        if (addr == raddr(MFRC522TestAccess::REG_FIFO_DATA) && buf_len == 1) {
            if (_fifoChunks.empty()) {
                buf[0] = 0;
                return;
            }
            buf[0] = _fifoChunks.front().front();
            _fifoChunks.front().erase(_fifoChunks.front().begin());
            if (_fifoChunks.front().empty()) _fifoChunks.pop_front();
            return;
        }
        I2CConnectionMock::_write_read(data, data_len, buf, buf_len);
    }

private:
    std::deque<std::vector<uint8_t>> _fifoChunks;
};

// Set the constant "transceive completed" IRQ/error signal for every
// _card_command(TRANSCEIVE) round-trip on this connection (every call reads
// these fresh, and every test here wants the same outcome for all of a
// sequence's steps), then queue one response chunk.
static void prep_transceive(FifoAwareMock& conn, uint8_t com_irq = 0x30, uint8_t error = 0x00,
                             const std::vector<uint8_t>* fifo_bytes = nullptr) {
    conn.setRegister(raddr(MFRC522TestAccess::REG_COM_IRQ), {com_irq});
    conn.setRegister(raddr(MFRC522TestAccess::REG_ERROR), {error});
    if (fifo_bytes) conn.queueFifo(*fifo_bytes);
}

static std::vector<std::vector<uint8_t>> writes_at(const FifoAwareMock& conn, uint8_t addr) {
    std::vector<std::vector<uint8_t>> out;
    for (const auto& w : conn.writes()) {
        if (w.size() == 2 && w[0] == addr) out.push_back(w);
    }
    return out;
}

int main() {
    // --- construction / init sequence ---------------------------------------
    FifoAwareMock connection;
    MFRC522TestAccess sensor(connection);
    check_true(true, "init");

    check_true(writes_at(connection, waddr(MFRC522TestAccess::REG_COMMAND))[0][1] == 0x0F,
               "init_soft_reset");
    check_true(writes_at(connection, waddr(MFRC522TestAccess::REG_T_MODE)).back()[1] == 0x80,
               "init_timer_mode");
    check_true(writes_at(connection, waddr(MFRC522TestAccess::REG_T_PRESCALER)).back()[1] == 0xA9,
               "init_timer_prescaler");
    check_true(writes_at(connection, waddr(MFRC522TestAccess::REG_T_RELOAD_H)).back()[1] == 0x03,
               "init_timer_reload_h");
    check_true(writes_at(connection, waddr(MFRC522TestAccess::REG_T_RELOAD_L)).back()[1] == 0xE8,
               "init_timer_reload_l");
    check_true(writes_at(connection, waddr(MFRC522TestAccess::REG_TX_ASK)).back()[1] == 0x40,
               "init_force_100_ask");
    check_true(writes_at(connection, waddr(MFRC522TestAccess::REG_MODE)).back()[1] == 0x3D,
               "init_mode_crc_a");
    check_true((connection.registers().at(waddr(MFRC522TestAccess::REG_TX_CONTROL)) & 0x03) == 0x03,
               "init_antenna_on");

    // --- is_card_present(): REQA -> 2-byte ATQA ------------------------------
    FifoAwareMock connection2;
    MFRC522TestAccess sensor2(connection2);
    std::vector<uint8_t> atqa = {0x04, 0x00};
    prep_transceive(connection2, 0x30, 0x00, &atqa);
    check_true(sensor2.is_card_present() == true, "is_card_present_true");

    FifoAwareMock connection3;
    MFRC522TestAccess sensor3(connection3);
    prep_transceive(connection3, 0x01);  // TimerIRq only -> no card
    check_true(sensor3.is_card_present() == false, "is_card_present_false");

    // --- read_uid(): single cascade level (4-byte UID) -----------------------
    FifoAwareMock connection4;
    MFRC522TestAccess sensor4(connection4);
    uint8_t uid_bytes[4] = {0x12, 0x34, 0x56, 0x78};
    uint8_t bcc = 0;
    for (uint8_t b : uid_bytes) bcc ^= b;

    connection4.setRegister(raddr(MFRC522TestAccess::REG_COM_IRQ), {0x30});
    connection4.setRegister(raddr(MFRC522TestAccess::REG_ERROR), {0x00});
    // Every _calc_crc() call in this flow (inside _select() and _halt_card())
    // polls DIV_IRQ, which defaults to 0 (never set here) - it just runs its
    // full bounded retry loop and returns a placeholder CRC, which is fine:
    // the mock's transceive success is keyed on COM_IRQ/ERROR, not on the CRC
    // bytes actually being cryptographically correct.
    // REQA response (is_card_present(), called first by read_uid())
    connection4.queueFifo({0x04, 0x00});
    // Anticollision CL1 response: 4 UID bytes + BCC
    connection4.queueFifo({uid_bytes[0], uid_bytes[1], uid_bytes[2], uid_bytes[3], bcc});
    // Select CL1 response: SAK with completion bit clear (0x00 = complete, single-size UID)
    connection4.queueFifo({0x00});
    // HLTA (halt) - result ignored by the driver, no response bytes needed

    uint8_t uid[10];
    size_t uid_len = 0;
    bool uid_ok = sensor4.read_uid(uid, uid_len);
    check_true(uid_ok && uid_len == 4 &&
               uid[0] == uid_bytes[0] && uid[1] == uid_bytes[1] &&
               uid[2] == uid_bytes[2] && uid[3] == uid_bytes[3],
               "read_uid");

    FifoAwareMock connection5;
    MFRC522TestAccess sensor5(connection5);
    connection5.setRegister(raddr(MFRC522TestAccess::REG_COM_IRQ), {0x01});  // TimerIRq only -> no card
    uint8_t uid5[10];
    size_t uid5_len = 0;
    check_true(sensor5.read_uid(uid5, uid5_len) == false, "read_uid_none");

    // --- antenna control ------------------------------------------------------
    FifoAwareMock connection6;
    MFRC522TestAccess sensor6(connection6);
    sensor6.antenna_off();
    check_true((connection6.registers().at(waddr(MFRC522TestAccess::REG_TX_CONTROL)) & 0x03) == 0x00,
               "antenna_off");
    sensor6.antenna_on();
    check_true((connection6.registers().at(waddr(MFRC522TestAccess::REG_TX_CONTROL)) & 0x03) == 0x03,
               "antenna_on");

    sensor6.set_antenna_gain(38);
    check_true((connection6.registers().at(waddr(MFRC522TestAccess::REG_RF_CFG)) & 0x70) == 0x50,
               "set_antenna_gain");
    connection6.setRegister(raddr(MFRC522TestAccess::REG_RF_CFG), {0x60});
    check_true(sensor6.antenna_gain() == 43, "antenna_gain");

    // set_antenna_gain(99) is invalid (no dB table entry): unlike Python
    // (which raises ValueError), this C++ API has no exception path - it
    // returns before ever touching RF_CFG. Confirm the invalid call is a
    // true no-op (no new write) rather than a crash or silent corruption.
    size_t rfCfgWritesBefore = writes_at(connection6, waddr(MFRC522TestAccess::REG_RF_CFG)).size();
    sensor6.set_antenna_gain(99);
    check_true(writes_at(connection6, waddr(MFRC522TestAccess::REG_RF_CFG)).size() == rfCfgWritesBefore,
               "set_antenna_gain_invalid_is_noop");

    // --- version() --------------------------------------------------------------
    FifoAwareMock connection7;
    MFRC522TestAccess sensor7(connection7);
    connection7.setRegister(raddr(MFRC522TestAccess::REG_VERSION), {0x92});  // chip_type=9, version=2
    uint8_t chip_type7, ver7;
    sensor7.version(chip_type7, ver7);
    check_true(chip_type7 == 9 && ver7 == 2, "version");

    // --- self_test(): FIFO fills to >=64 bytes on the first CalcCRC iteration, --
    // then read_fifo(64) must return the exact v1.0 reference table.
    FifoAwareMock connection8;
    MFRC522TestAccess sensor8(connection8);
    connection8.setRegister(raddr(MFRC522TestAccess::REG_VERSION), {0x91});  // version=1 -> v1.0 reference table
    static const std::vector<uint8_t> ref_v10 = {
        0x00, 0x87, 0x98, 0x0F, 0x49, 0xFF, 0x07, 0x19,
        0xBF, 0x22, 0x30, 0x49, 0x59, 0x63, 0xAD, 0xCA,
        0x7F, 0xE3, 0x4E, 0x03, 0x5C, 0x4E, 0x49, 0x50,
        0x47, 0x9A, 0x37, 0x61, 0xE7, 0xE2, 0xC6, 0x2E,
        0x75, 0x5A, 0xED, 0x04, 0x3D, 0x02, 0x4B, 0x78,
        0x32, 0xFF, 0x58, 0x3B, 0x7C, 0xE9, 0x00, 0x94,
        0xB4, 0x4A, 0x59, 0x5B, 0xFD, 0xC9, 0x29, 0xDF,
        0x35, 0x96, 0x98, 0x9E, 0x4F, 0x30, 0x32, 0x8D,
    };
    connection8.queueFifo(ref_v10);
    check_true(sensor8.self_test() == true, "self_test_pass");

    // --- authenticate() / stop_crypto() ------------------------------------------
    FifoAwareMock connection9;
    MFRC522TestAccess sensor9(connection9);
    connection9.setRegister(raddr(MFRC522TestAccess::REG_STATUS_2), {0x08});  // MFCrypto1On set immediately
    uint8_t key[6] = {0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF};
    bool ok = sensor9.authenticate(4, MFRC522Full::KEY_A, key, uid_bytes);
    check_true(ok == true, "authenticate");

    sensor9.stop_crypto();
    check_true((connection9.registers().at(waddr(MFRC522TestAccess::REG_STATUS_2)) & 0x08) == 0x00,
               "stop_crypto");

    // Note: unlike Python's authenticate(), this C++ API takes fixed-size raw
    // pointers (6-byte key, 4-byte uid) with no length parameter, so there is
    // no bad-key-length rejection path to test here - confirmed absent by
    // reading MFRC522.h/.cpp (authenticate() has no length guard at all).

    // --- read_block() / write_block() (via the CRC + transceive mocked flow) ----
    FifoAwareMock connection10;
    MFRC522TestAccess sensor10(connection10);
    uint8_t block_data[16];
    for (int i = 0; i < 16; i++) block_data[i] = (uint8_t)i;
    // _calc_crc() polls DIV_IRQ; make it show CRCIRq set immediately, and preload
    // CRC_RESULT_H/L with a fixed placeholder - the driver just forwards
    // whatever the chip returns as the trailing 2 command bytes, so any
    // placeholder value round-trips correctly.
    connection10.setRegister(raddr(MFRC522TestAccess::REG_DIV_IRQ), {0x04});
    connection10.setRegister(raddr(MFRC522TestAccess::REG_CRC_RESULT_H), {0xAB});
    connection10.setRegister(raddr(MFRC522TestAccess::REG_CRC_RESULT_L), {0xCD});
    connection10.setRegister(raddr(MFRC522TestAccess::REG_COM_IRQ), {0x30});
    connection10.setRegister(raddr(MFRC522TestAccess::REG_ERROR), {0x00});
    std::vector<uint8_t> block_vec(block_data, block_data + 16);
    connection10.queueFifo(block_vec);
    uint8_t got[16];
    check_true(sensor10.read_block(4, got) == true, "read_block");
    check_true(std::vector<uint8_t>(got, got + 16) == block_vec, "read_block_data");

    FifoAwareMock connection11;
    MFRC522TestAccess sensor11(connection11);
    connection11.setRegister(raddr(MFRC522TestAccess::REG_DIV_IRQ), {0x04});
    connection11.setRegister(raddr(MFRC522TestAccess::REG_CRC_RESULT_H), {0xAB});
    connection11.setRegister(raddr(MFRC522TestAccess::REG_CRC_RESULT_L), {0xCD});
    connection11.setRegister(raddr(MFRC522TestAccess::REG_COM_IRQ), {0x30});
    connection11.setRegister(raddr(MFRC522TestAccess::REG_ERROR), {0x00});
    connection11.queueFifo({0x0A});  // phase 1 ACK (0x0A in low nibble)
    connection11.queueFifo({0x0A});  // phase 2 ACK
    check_true(sensor11.write_block(4, block_data) == true, "write_block");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
