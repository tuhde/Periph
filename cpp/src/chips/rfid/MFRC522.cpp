#include "MFRC522.h"
#include <string.h>

#ifndef ARDUINO
#include <unistd.h>
static inline void arduino_delay(unsigned long ms) { usleep(ms * 1000UL); }
#endif

void MFRC522Minimal::_delay_ms(unsigned long ms) {
#ifdef ARDUINO
    delay(ms);
#else
    arduino_delay(ms);
#endif
}

MFRC522Minimal::MFRC522Minimal(Transport& transport, uint8_t bus_type)
    : _transport(transport), _bus_type(bus_type) {
    _init_chip();
}

uint8_t MFRC522Minimal::_addr_for(uint8_t reg, bool read) const {
    if (_bus_type == BUS_SPI) {
        return ((reg << 1) & 0x7E) | (read ? 0x80 : 0x00);
    }
    if (_bus_type == BUS_UART) {
        return (reg & 0x3F) | (read ? 0x80 : 0x00);
    }
    return reg & 0x3F;
}

void MFRC522Minimal::_write_reg(uint8_t reg, uint8_t value) {
    uint8_t buf[2];
    buf[0] = _addr_for(reg, false);
    buf[1] = value;
    _transport.write(buf, 2);
}

uint8_t MFRC522Minimal::_read_reg(uint8_t reg) {
    uint8_t addr = _addr_for(reg, true);
    uint8_t b = 0;
    _transport.write_read(&addr, 1, &b, 1);
    return b;
}

void MFRC522Minimal::_set_bits(uint8_t reg, uint8_t mask) {
    _write_reg(reg, _read_reg(reg) | mask);
}

void MFRC522Minimal::_clear_bits(uint8_t reg, uint8_t mask) {
    _write_reg(reg, _read_reg(reg) & ~mask);
}

void MFRC522Minimal::_init_chip() {
    _write_reg(REG_COMMAND, CMD_SOFT_RESET);
    // Wait for PowerDown bit to clear (oscillator started)
    for (int i = 0; i < 50; i++) {
        if ((_read_reg(REG_COMMAND) & 0x10) == 0) break;
        _delay_ms(1);
    }
    _delay_ms(50);
    // Timer: ~25 ms auto-timeout
    _write_reg(REG_T_MODE,      0x80);
    _write_reg(REG_T_PRESCALER, 0xA9);
    _write_reg(REG_T_RELOAD_H,  0x03);
    _write_reg(REG_T_RELOAD_L,  0xE8);
    // Force100ASK
    _write_reg(REG_TX_ASK, 0x40);
    // Mode: TxWaitRF, PolMFin, CRCPreset=0x6363 (CRC_A)
    _write_reg(REG_MODE, 0x3D);
    // Antenna on
    _set_bits(REG_TX_CONTROL, 0x03);
}

void MFRC522Minimal::_read_fifo(uint8_t* out, size_t n) {
    for (size_t i = 0; i < n; i++) {
        out[i] = _read_reg(REG_FIFO_DATA);
    }
}

void MFRC522Minimal::_write_fifo(const uint8_t* data, size_t n) {
    for (size_t i = 0; i < n; i++) {
        _write_reg(REG_FIFO_DATA, data[i]);
    }
}

void MFRC522Minimal::_flush_fifo() {
    _write_reg(REG_FIFO_LEVEL, FIFO_FLUSH);
}

bool MFRC522Minimal::_card_command(uint8_t command, uint8_t wait_irq, const uint8_t* send_data, size_t send_len) {
    _write_reg(REG_COMMAND, CMD_IDLE);
    _write_reg(REG_COM_IRQ, 0x7F);
    _flush_fifo();
    if (send_data && send_len) {
        _write_fifo(send_data, send_len);
    }
    _write_reg(REG_COMMAND, command);
    if (command == CMD_TRANSCEIVE) {
        _set_bits(REG_BIT_FRAMING, 0x80);
    }
    for (int i = 0; i < 200; i++) {
        uint8_t n = _read_reg(REG_COM_IRQ);
        if (n & wait_irq) return true;
        if (n & 0x01) return false;
    }
    return false;
}

bool MFRC522Minimal::_transceive(const uint8_t* send, size_t send_len,
                                 uint8_t* back_data, size_t& back_len, uint8_t valid_bits) {
    back_len = 0;
    if (!_card_command(CMD_TRANSCEIVE, IRQ_RX | IRQ_IDLE, send, send_len)) {
        return false;
    }
    uint8_t err = _read_reg(REG_ERROR);
    if (err & 0x13) {  // BufferOvfl | ParityErr | ProtocolErr
        return false;
    }
    uint8_t fifo_level = _read_reg(REG_FIFO_LEVEL);
    if (fifo_level == 0) return false;
    if (back_data) {
        _read_fifo(back_data, fifo_level);
    }
    back_len = fifo_level;
    (void)valid_bits;
    return true;
}

bool MFRC522Minimal::_calc_crc(const uint8_t* data, size_t len, uint8_t out[2]) {
    _write_reg(REG_COMMAND, CMD_IDLE);
    _write_reg(REG_DIV_IRQ, 0x04);
    _flush_fifo();
    if (data && len) {
        _write_fifo(data, len);
    }
    _write_reg(REG_COMMAND, CMD_CALC_CRC);
    for (int i = 0; i < 100; i++) {
        if (_read_reg(REG_DIV_IRQ) & 0x04) break;
        _delay_ms(1);
    }
    _write_reg(REG_COMMAND, CMD_IDLE);
    out[0] = _read_reg(REG_CRC_RESULT_H);
    out[1] = _read_reg(REG_CRC_RESULT_L);
    return true;
}

bool MFRC522Minimal::is_card_present() {
    _write_reg(REG_BIT_FRAMING, 0x07);
    _write_reg(REG_TX_MODE, 0x00);
    _write_reg(REG_RX_MODE, 0x00);
    uint8_t send = PICC_REQA;
    uint8_t back[16];
    size_t  back_len = 0;
    if (!_transceive(&send, 1, back, back_len, 0)) return false;
    return back_len == 2;
}

bool MFRC522Minimal::_anticollision(uint8_t cmd, uint8_t* ser_num) {
    _write_reg(REG_TX_MODE, 0x00);
    _write_reg(REG_RX_MODE, 0x00);
    _write_reg(REG_BIT_FRAMING, 0x00);

    uint8_t send[7];
    send[0] = cmd;
    send[1] = 0x20;
    memset(send + 2, 0, 5);
    uint8_t back[8];
    size_t  back_len = 0;
    _delay_ms(1);
    if (!_transceive(send, 7, back, back_len, 0)) return false;
    if (back_len != 5) return false;
    memcpy(ser_num, back, 4);
    uint8_t bcc = 0;
    for (int i = 0; i < 4; i++) bcc ^= ser_num[i];
    if (bcc != back[4]) return false;
    return true;
}

bool MFRC522Minimal::_select(uint8_t cmd, const uint8_t* uid_part, uint8_t* sak) {
    uint8_t buf[9];
    buf[0] = cmd;
    buf[1] = PICC_SEL_BIT;
    memcpy(buf + 2, uid_part, 4);
    uint8_t bcc = 0;
    for (int i = 0; i < 4; i++) bcc ^= uid_part[i];
    buf[6] = bcc;
    uint8_t crc[2];
    _calc_crc(buf, 7, crc);
    buf[7] = crc[0];
    buf[8] = crc[1];
    _write_reg(REG_TX_MODE, 0x80);
    _write_reg(REG_RX_MODE, 0x80);
    _delay_ms(1);
    uint8_t back[4];
    size_t  back_len = 0;
    bool ok = _transceive(buf, 9, back, back_len, 0);
    _write_reg(REG_TX_MODE, 0x00);
    _write_reg(REG_RX_MODE, 0x00);
    if (!ok || back_len < 1) return false;
    *sak = back[0];
    return true;
}

bool MFRC522Minimal::_select_card(uint8_t* out, size_t& len) {
    len = 0;
    uint8_t cascade[3][2] = {
        {PICC_ANTICOLL_CL1, PICC_SELECT_CL1},
        {PICC_ANTICOLL_CL2, PICC_SELECT_CL2},
        {PICC_ANTICOLL_CL3, PICC_SELECT_CL3},
    };
    for (int i = 0; i < 3; i++) {
        uint8_t part[4];
        if (!_anticollision(cascade[i][0], part)) return false;
        uint8_t sak = 0;
        if (!_select(cascade[i][1], part, &sak)) return false;
        if (!(sak & PICC_SAK_NOT_COMPLETE)) {
            size_t off = len;
            if (part[0] == PICC_CT) {
                memcpy(out + off, part + 1, 3);
                len += 3;
            } else {
                memcpy(out + off, part, 4);
                len += 4;
            }
            (void)off;
            return true;
        } else {
            memcpy(out + len, part + 1, 3);
            len += 3;
        }
    }
    return false;
}

void MFRC522Minimal::_halt_card() {
    uint8_t buf[4];
    buf[0] = PICC_HLTA;
    buf[1] = 0x00;
    _write_reg(REG_TX_MODE, 0x80);
    _write_reg(REG_RX_MODE, 0x80);
    uint8_t crc[2];
    _calc_crc(buf, 2, crc);
    buf[2] = crc[0];
    buf[3] = crc[1];
    _delay_ms(1);
    _card_command(CMD_TRANSCEIVE, IRQ_RX | IRQ_IDLE, buf, 4);
    _write_reg(REG_TX_MODE, 0x00);
    _write_reg(REG_RX_MODE, 0x00);
}

bool MFRC522Minimal::read_uid(uint8_t* out, size_t& len) {
    if (!is_card_present()) {
        len = 0;
        return false;
    }
    bool ok = _select_card(out, len);
    _halt_card();
    return ok;
}

// --- Full ---

MFRC522Full::MFRC522Full(Transport& transport, uint8_t bus_type)
    : MFRC522Minimal(transport, bus_type) {
}

void MFRC522Full::reset() {
    _init_chip();
}

void MFRC522Full::antenna_on() {
    _set_bits(REG_TX_CONTROL, 0x03);
}

void MFRC522Full::antenna_off() {
    _clear_bits(REG_TX_CONTROL, 0x03);
}

void MFRC522Full::set_antenna_gain(uint8_t dB) {
    static const uint8_t table[6] = {
        RX_GAIN_18_DB, RX_GAIN_23_DB, RX_GAIN_33_DB,
        RX_GAIN_38_DB, RX_GAIN_43_DB, RX_GAIN_48_DB,
    };
    uint8_t gain = 0xFF;
    for (uint8_t i = 0; i < 6; i++) {
        static const uint8_t dbs[6] = {18, 23, 33, 38, 43, 48};
        if (dbs[i] == dB) { gain = table[i]; break; }
    }
    if (gain == 0xFF) return;
    uint8_t cur = _read_reg(REG_RF_CFG) & 0x8F;
    _write_reg(REG_RF_CFG, cur | gain);
}

uint8_t MFRC522Full::antenna_gain() {
    uint8_t cur = _read_reg(REG_RF_CFG) & 0x70;
    static const uint8_t gain[6] = {0x00, 0x10, 0x40, 0x50, 0x60, 0x70};
    static const uint8_t dB[6]   = {18,  23,  33,  38,  43,  48};
    for (uint8_t i = 0; i < 6; i++) {
        if (gain[i] == cur) return dB[i];
    }
    return 0;
}

void MFRC522Full::version(uint8_t& chip_type, uint8_t& version) {
    uint8_t raw = _read_reg(REG_VERSION);
    chip_type = (raw >> 4) & 0x0F;
    version = raw & 0x0F;
}

bool MFRC522Full::self_test() {
    static const uint8_t ref_v10[64] = {
        0x00, 0x87, 0x98, 0x0F, 0x49, 0xFF, 0x07, 0x19,
        0xBF, 0x22, 0x30, 0x49, 0x59, 0x63, 0xAD, 0xCA,
        0x7F, 0xE3, 0x4E, 0x03, 0x5C, 0x4E, 0x49, 0x50,
        0x47, 0x9A, 0x37, 0x61, 0xE7, 0xE2, 0xC6, 0x2E,
        0x75, 0x5A, 0xED, 0x04, 0x3D, 0x02, 0x4B, 0x78,
        0x32, 0xFF, 0x58, 0x3B, 0x7C, 0xE9, 0x00, 0x94,
        0xB4, 0x4A, 0x59, 0x5B, 0xFD, 0xC9, 0x29, 0xDF,
        0x35, 0x96, 0x98, 0x9E, 0x4F, 0x30, 0x32, 0x8D,
    };
    static const uint8_t ref_v20[64] = {
        0x00, 0xEB, 0x66, 0xBA, 0x57, 0xBF, 0x23, 0x95,
        0xD0, 0xE3, 0x0D, 0x3D, 0x27, 0x89, 0x5C, 0xDE,
        0x9D, 0x3B, 0xA7, 0x00, 0x21, 0x5B, 0x89, 0x82,
        0x51, 0x3A, 0xEB, 0x02, 0x0C, 0xA5, 0x00, 0x49,
        0x7C, 0x84, 0x4D, 0xB3, 0xCC, 0xD2, 0x1B, 0x81,
        0x5D, 0x48, 0x76, 0xD5, 0x71, 0x61, 0x21, 0xA9,
        0x86, 0x96, 0x83, 0x38, 0xCF, 0x9D, 0x5B, 0x6D,
        0xDC, 0x15, 0xBA, 0x3E, 0x7D, 0x95, 0x3B, 0x2F,
    };
    uint8_t chip_type, ver;
    version(chip_type, ver);
    const uint8_t* ref = (ver == 1) ? ref_v10 : ref_v20;

    _write_reg(REG_AUTO_TEST, 0x09);
    _write_reg(REG_FIFO_LEVEL, FIFO_FLUSH);
    _write_reg(REG_COMMAND, CMD_IDLE);
    for (int i = 0; i < 255; i++) {
        uint8_t n = _read_reg(REG_FIFO_LEVEL);
        if (n >= 64) break;
        _write_reg(REG_COMMAND, CMD_CALC_CRC);
        _delay_ms(1);
    }
    _write_reg(REG_AUTO_TEST, 0x00);
    _write_reg(REG_COMMAND, CMD_SOFT_RESET);
    _delay_ms(50);
    _init_chip();

    uint8_t got[64];
    _read_fifo(got, 64);
    for (int i = 0; i < 64; i++) {
        if (got[i] != ref[i]) return false;
    }
    return true;
}

bool MFRC522Full::wakeup_card() {
    _write_reg(REG_BIT_FRAMING, 0x07);
    _write_reg(REG_TX_MODE, 0x00);
    _write_reg(REG_RX_MODE, 0x00);
    uint8_t send = PICC_WUPA;
    uint8_t back[16];
    size_t  back_len = 0;
    if (!_transceive(&send, 1, back, back_len, 0)) return false;
    return back_len == 2;
}

bool MFRC522Full::select_card(uint8_t* out, size_t& len) {
    if (!wakeup_card()) {
        len = 0;
        return false;
    }
    return _select_card(out, len);
}

void MFRC522Full::halt_card() {
    _halt_card();
}

bool MFRC522Full::authenticate(uint8_t block_address, uint8_t key_type,
                                const uint8_t* key, const uint8_t* uid) {
    uint8_t buf[12];
    buf[0] = key_type;
    buf[1] = block_address;
    memcpy(buf + 2, key, 6);
    memcpy(buf + 8, uid, 4);
    _write_reg(REG_COM_IRQ, IRQ_ALL);
    _write_reg(REG_STATUS_2, 0x00);
    _flush_fifo();
    _write_fifo(buf, 12);
    _write_reg(REG_COMMAND, CMD_MFAUTHENT);
    for (int i = 0; i < 200; i++) {
        uint8_t n = _read_reg(REG_STATUS_2);
        if (n & STATUS_2_CRYPTO1ON) return true;
        _delay_ms(1);
    }
    return false;
}

void MFRC522Full::stop_crypto() {
    _clear_bits(REG_STATUS_2, STATUS_2_CRYPTO1ON);
}

bool MFRC522Full::read_block(uint8_t block_address, uint8_t* out) {
    uint8_t cmd[2] = {0x30, block_address};
    _write_reg(REG_TX_MODE, 0x80);
    _write_reg(REG_RX_MODE, 0x80);
    uint8_t crc[2];
    _calc_crc(cmd, 2, crc);
    uint8_t full[4] = {cmd[0], cmd[1], crc[0], crc[1]};
    uint8_t back[18];
    size_t  back_len = 0;
    bool ok = _transceive(full, 4, back, back_len, 0);
    _write_reg(REG_TX_MODE, 0x00);
    _write_reg(REG_RX_MODE, 0x00);
    if (!ok || back_len != 16) return false;
    memcpy(out, back, 16);
    return true;
}

bool MFRC522Full::_value_op(uint8_t cmd, uint8_t block_address, uint32_t delta, bool dummy) {
    uint8_t c[2] = {cmd, block_address};
    _write_reg(REG_TX_MODE, 0x80);
    _write_reg(REG_RX_MODE, 0x80);
    uint8_t crc[2];
    _calc_crc(c, 2, crc);
    uint8_t full[4] = {c[0], c[1], crc[0], crc[1]};
    uint8_t back[4];
    size_t  back_len = 0;
    if (!_transceive(full, 4, back, back_len, 4)) {
        _write_reg(REG_TX_MODE, 0x00);
        _write_reg(REG_RX_MODE, 0x00);
        return false;
    }
    if (back_len < 1 || (back[0] & 0x0F) != 0x0A) {
        _write_reg(REG_TX_MODE, 0x00);
        _write_reg(REG_RX_MODE, 0x00);
        return false;
    }
    uint8_t data[6];
    if (dummy) {
        memset(data, 0, 4);
    } else {
        data[0] = delta & 0xFF;
        data[1] = (delta >> 8) & 0xFF;
        data[2] = (delta >> 16) & 0xFF;
        data[3] = (delta >> 24) & 0xFF;
    }
    _calc_crc(data, 4, crc);
    data[4] = crc[0];
    data[5] = crc[1];
    back_len = 0;
    bool ok = _transceive(data, 6, back, back_len, 4);
    _write_reg(REG_TX_MODE, 0x00);
    _write_reg(REG_RX_MODE, 0x00);
    if (!ok || back_len < 1 || (back[0] & 0x0F) != 0x0A) return false;
    return true;
}

bool MFRC522Full::_transfer(uint8_t block_address) {
    uint8_t c[2] = {0xB0, block_address};
    _write_reg(REG_TX_MODE, 0x80);
    _write_reg(REG_RX_MODE, 0x80);
    uint8_t crc[2];
    _calc_crc(c, 2, crc);
    uint8_t full[4] = {c[0], c[1], crc[0], crc[1]};
    uint8_t back[4];
    size_t  back_len = 0;
    bool ok = _transceive(full, 4, back, back_len, 4);
    _write_reg(REG_TX_MODE, 0x00);
    _write_reg(REG_RX_MODE, 0x00);
    if (!ok || back_len < 1 || (back[0] & 0x0F) != 0x0A) return false;
    return true;
}

bool MFRC522Full::write_block(uint8_t block_address, const uint8_t* data) {
    if (!_value_op(0xA0, block_address, 0, false)) return false;
    return _transfer(block_address);
}

bool MFRC522Full::increment_value(uint8_t block_address, uint32_t delta) {
    if (!_value_op(0xC1, block_address, delta, false)) return false;
    return _transfer(block_address);
}

bool MFRC522Full::decrement_value(uint8_t block_address, uint32_t delta) {
    if (!_value_op(0xC0, block_address, delta, false)) return false;
    return _transfer(block_address);
}

bool MFRC522Full::restore_value(uint8_t block_address) {
    if (!_value_op(0xC2, block_address, 0, true)) return false;
    return _transfer(block_address);
}

bool MFRC522Full::transfer_value(uint8_t destination_block) {
    return _transfer(destination_block);
}

bool MFRC522Full::read_ultralight_page(uint8_t page_address, uint8_t* out) {
    uint8_t cmd[2] = {0x30, page_address};
    _write_reg(REG_TX_MODE, 0x80);
    _write_reg(REG_RX_MODE, 0x80);
    uint8_t crc[2];
    _calc_crc(cmd, 2, crc);
    uint8_t full[4] = {cmd[0], cmd[1], crc[0], crc[1]};
    uint8_t back[18];
    size_t  back_len = 0;
    bool ok = _transceive(full, 4, back, back_len, 0);
    _write_reg(REG_TX_MODE, 0x00);
    _write_reg(REG_RX_MODE, 0x00);
    if (!ok || back_len != 16) return false;
    memcpy(out, back, 16);
    return true;
}

bool MFRC522Full::write_ultralight_page(uint8_t page_address, const uint8_t* data) {
    uint8_t buf[8];
    buf[0] = 0xA2;
    buf[1] = page_address;
    memcpy(buf + 2, data, 4);
    _write_reg(REG_TX_MODE, 0x80);
    _write_reg(REG_RX_MODE, 0x80);
    uint8_t crc[2];
    _calc_crc(buf, 6, crc);
    buf[6] = crc[0];
    buf[7] = crc[1];
    uint8_t back[4];
    size_t  back_len = 0;
    bool ok = _transceive(buf, 8, back, back_len, 4);
    _write_reg(REG_TX_MODE, 0x00);
    _write_reg(REG_RX_MODE, 0x00);
    if (!ok || back_len < 1 || (back[0] & 0x0F) != 0x0A) return false;
    return true;
}

void MFRC522Full::generate_random_id(uint8_t* out) {
    _write_reg(REG_COMMAND, CMD_IDLE);
    _write_reg(REG_COM_IRQ, IRQ_ALL);
    _write_reg(REG_DIV_IRQ, 0x14);
    _write_reg(REG_COMMAND, CMD_RANDOM_ID);
    for (int i = 0; i < 50; i++) {
        if (_read_reg(REG_COM_IRQ) & 0x10) break;
        _delay_ms(1);
    }
    _write_reg(REG_COMMAND, CMD_IDLE);
    _read_fifo(out, 10);
}
