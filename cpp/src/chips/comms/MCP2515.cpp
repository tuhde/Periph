#include "MCP2515.h"

uint8_t _MCP2515Base::_rx_data_buf[8];

#ifdef __linux__
#include <stdio.h>
#include <time.h>
static unsigned long _now_ms_linux() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (unsigned long)(ts.tv_sec) * 1000UL + (unsigned long)(ts.tv_nsec / 1000000L);
}
#define _millis_impl() _now_ms_linux()
#elif defined(ARDUINO)
#include <Arduino.h>
#define _millis_impl() millis()
#elif defined(__ZEPHYR__)
#include <zephyr/kernel.h>
#define _millis_impl() ((unsigned long)k_uptime_get())
#elif defined(ESP_PLATFORM)
#include <esp_timer.h>
#define _millis_impl() ((unsigned long)(esp_timer_get_time() / 1000ULL))
#elif defined(PICO_SDK_VERSION_MAJOR)
#include "pico/stdlib.h"
#define _millis_impl() ((unsigned long)(to_ms_since_boot(get_absolute_time())))
#else
#include <chrono>
static unsigned long _now_ms_host() {
    using namespace std::chrono;
    return (unsigned long)duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
}
#define _millis_impl() _now_ms_host()
#endif

void _MCP2515Base::_delay_ms(unsigned long ms) {
    unsigned long start = _millis_impl();
    while ((unsigned long)(_millis_impl() - start) < ms) {
        // busy-wait
    }
}

void _MCP2515Base::_write_reg(uint8_t reg, uint8_t value) {
    uint8_t buf[3] = { INSTR_WRITE, reg, value };
    _connection.write(buf, 3);
}

uint8_t _MCP2515Base::_read_reg(uint8_t reg) const {
    uint8_t cmd[2] = { INSTR_READ, reg };
    uint8_t val = 0;
    _connection.write_read(cmd, 2, &val, 1);
    return val;
}

void _MCP2515Base::_modify_reg(uint8_t reg, uint8_t mask, uint8_t value) {
    uint8_t buf[4] = { INSTR_BIT_MODIFY, reg, mask, value };
    _connection.write(buf, 4);
}

uint8_t _MCP2515Base::_read_status() {
    uint8_t cmd = INSTR_READ_STATUS;
    uint8_t val = 0;
    _connection.write_read(&cmd, 1, &val, 1);
    return val;
}

void _MCP2515Base::_rts(uint8_t mask) {
    uint8_t buf[1] = { (uint8_t)(INSTR_RTS | (mask & 0x07)) };
    _connection.write(buf, 1);
}

void _MCP2515Base::_wait_op_mode(uint8_t target, uint32_t timeout_ms) {
    unsigned long start = _millis_impl();
    while ((unsigned long)(_millis_impl() - start) < timeout_ms) {
        if ((_read_reg(REG_CANSTAT) & CANSTAT_OPMOD_MASK) == (target & CANSTAT_OPMOD_MASK)) {
            return;
        }
        _delay_ms(1);
    }
}

void _MCP2515Base::_cnf_for(uint16_t bitrate_kbps, uint8_t osc_mhz,
                              uint8_t& cnf1, uint8_t& cnf2, uint8_t& cnf3) {
    // Pre-computed CNF values (BTLMODE=1, SAM=0, SJW=1 TQ).
    // See specs/comms/mcp2515.md "Pre-computed CNF register values".
    if (osc_mhz == 8) {
        switch (bitrate_kbps) {
            case 125:  cnf1 = 0x01; cnf2 = 0xBA; cnf3 = 0x03; break;
            case 250:  cnf1 = 0x00; cnf2 = 0xBA; cnf3 = 0x03; break;
            case 500:  cnf1 = 0x00; cnf2 = 0x91; cnf3 = 0x01; break;
            case 1000: cnf1 = 0x00; cnf2 = 0x80; cnf3 = 0x00; break;
            default:   cnf1 = 0x01; cnf2 = 0xBA; cnf3 = 0x03; break;
        }
    } else {
        switch (bitrate_kbps) {
            case 125:  cnf1 = 0x03; cnf2 = 0xBA; cnf3 = 0x03; break;
            case 250:  cnf1 = 0x01; cnf2 = 0xBA; cnf3 = 0x03; break;
            case 500:  cnf1 = 0x00; cnf2 = 0xBA; cnf3 = 0x03; break;
            case 1000: cnf1 = 0x00; cnf2 = 0x91; cnf3 = 0x01; break;
            default:   cnf1 = 0x03; cnf2 = 0xBA; cnf3 = 0x03; break;
        }
    }
}

void _MCP2515Base::_pack_id(uint32_t id, bool extended,
                             uint8_t& sidh, uint8_t& sidl,
                             uint8_t& eid8, uint8_t& eid0) {
    if (extended) {
        sidh = (id >> 21) & 0xFF;
        sidl = (uint8_t)(((id >> 18) & 0x07) << 5) | 0x08 | ((id >> 16) & 0x03);
        eid8 = (id >> 8) & 0xFF;
        eid0 = id & 0xFF;
    } else {
        sidh = (id >> 3) & 0xFF;
        sidl = (uint8_t)((id & 0x07) << 5);
        eid8 = 0;
        eid0 = 0;
    }
}

uint32_t _MCP2515Base::_unpack_id(uint8_t sidh, uint8_t sidl,
                                   uint8_t eid8, uint8_t eid0, bool ide) {
    if (ide) {
        return ((uint32_t)sidh << 21)
             | ((uint32_t)(sidl >> 5) << 18)
             | ((uint32_t)(sidl & 0x03) << 16)
             | ((uint32_t)eid8 << 8)
             | eid0;
    }
    return ((uint32_t)sidh << 3) | (sidl >> 5);
}

void _MCP2515Base::_reset() {
    uint8_t cmd = INSTR_RESET;
    _connection.write(&cmd, 1);
}

_MCP2515Base::_MCP2515Base(Connection& connection, uint16_t bitrate_kbps, uint8_t osc_mhz)
    : _connection(connection), _bitrate_kbps(bitrate_kbps), _osc_mhz(osc_mhz) {
    _reset();
    _delay_ms(5);
    _wait_op_mode(CANSTAT_OPMOD_CONFIG);

    uint8_t cnf1, cnf2, cnf3;
    _cnf_for(bitrate_kbps, osc_mhz, cnf1, cnf2, cnf3);
    _write_reg(REG_CNF1, cnf1);
    _write_reg(REG_CNF2, cnf2);
    _write_reg(REG_CNF3, cnf3);

    _write_reg(REG_RXB0CTRL, RXB0CTRL_RXM_ANY | RXB0CTRL_BUKT);
    _write_reg(REG_RXB1CTRL, RXB0CTRL_RXM_ANY);

    _write_reg(REG_CANINTE, 0x00);

    _write_reg(REG_TXB0CTRL, TXBnCTRL_TXP_MASK);
    _write_reg(REG_TXB1CTRL, TXBnCTRL_TXP_MASK);
    _write_reg(REG_TXB2CTRL, TXBnCTRL_TXP_MASK);

    _write_reg(REG_CANCTRL, 0x00);  // REQOP = Normal
    _wait_op_mode(CANSTAT_OPMOD_NORMAL);
}

uint8_t _MCP2515Base::_send(uint32_t id, const uint8_t* data, uint8_t len,
                             bool extended, bool rtr, uint8_t buf_index) {
    if (len > 8) len = 8;

    if (buf_index == 0xFF) {
        uint8_t status = _read_status();
        if (!(status & 0x04)) buf_index = 0;
        else if (!(status & 0x10)) buf_index = 1;
        else if (!(status & 0x40)) buf_index = 2;
        else return 0xFF;  // all busy
    }

    uint8_t sidh, sidl, eid8, eid0;
    _pack_id(id, extended, sidh, sidl, eid8, eid0);

    uint8_t dlc = (uint8_t)(len | (rtr ? 0x40 : 0x00));
    uint8_t offset = (buf_index == 0) ? 0 : (buf_index == 1) ? 2 : 4;
    uint8_t instr = INSTR_LOAD_TX_BUF | offset;

    uint8_t payload[14];
    payload[0] = instr;
    payload[1] = sidh;
    payload[2] = sidl;
    payload[3] = eid8;
    payload[4] = eid0;
    payload[5] = dlc;
    for (uint8_t i = 0; i < 8; i++) {
        payload[6 + i] = (data && i < len) ? data[i] : 0;
    }
    _connection.write(payload, 14);

    _rts((uint8_t)(1 << buf_index));

    uint8_t tx_ctrl_reg = (buf_index == 0) ? REG_TXB0CTRL
                          : (buf_index == 1) ? REG_TXB1CTRL : REG_TXB2CTRL;
    unsigned long start = _millis_impl();
    while ((unsigned long)(_millis_impl() - start) < 1000) {
        if (!(_read_reg(tx_ctrl_reg) & TXBnCTRL_TXREQ)) {
            return buf_index;
        }
        _delay_ms(1);
    }
    return buf_index;
}

bool _MCP2515Base::_poll_rx(CanFrame& frame, uint32_t timeout_ms) {
    unsigned long start = _millis_impl();
    while (true) {
        uint8_t status = _read_status();
        uint8_t offset = 0xFF;
        if (status & CANINTF_RX0IF) offset = 0;
        else if (status & CANINTF_RX1IF) offset = 4;
        if (offset != 0xFF) {
            uint8_t cmd = (uint8_t)(INSTR_READ_RX_BUF | offset);
            uint8_t buf[13];
            _connection.write_read(&cmd, 1, buf, 13);
            bool ide = (buf[1] & 0x08) != 0;
            bool rtr = ide ? ((buf[1] & 0x10) != 0) : ((buf[4] & 0x40) != 0);
            frame.id = _unpack_id(buf[0], buf[1], buf[2], buf[3], ide);
            frame.dlc = buf[4] & 0x0F;
            for (uint8_t i = 0; i < frame.dlc && i < 8; i++) {
                _rx_data_buf[i] = buf[5 + i];
            }
            frame.data = _rx_data_buf;
            frame.extended = ide;
            frame.rtr = rtr;
            return true;
        }
        if (timeout_ms == 0) return false;
        unsigned long now = _millis_impl();
        if ((unsigned long)(now - start) >= timeout_ms) return false;
        _delay_ms(1);
    }
}

void _MCP2515Base::_set_mode(uint8_t mode) {
    _modify_reg(REG_CANCTRL, 0xE0, mode & 0xE0);
    _wait_op_mode(mode);
}

uint8_t _MCP2515Base::_get_mode() const {
    return _read_reg(REG_CANSTAT) & CANSTAT_OPMOD_MASK;
}

void _MCP2515Base::_set_filter(uint8_t filter_num, uint32_t id, bool extended) {
    if (filter_num > 5) return;
    uint8_t sidh, sidl, eid8, eid0;
    _pack_id(id, extended, sidh, sidl, eid8, eid0);
    uint8_t base = filter_num * 4;
    _write_reg(base,     sidh);
    _write_reg(base + 1, sidl);
    _write_reg(base + 2, eid8);
    _write_reg(base + 3, eid0);
}

void _MCP2515Base::_set_mask(uint8_t mask_num, uint32_t mask, bool extended) {
    if (mask_num > 1) return;
    uint8_t sidh, sidl, eid8, eid0;
    _pack_id(mask, extended, sidh, sidl, eid8, eid0);
    uint8_t base = (mask_num == 0) ? REG_RXM0SIDH : REG_RXM1SIDH;
    _write_reg(base,     sidh);
    _write_reg(base + 1, sidl);
    _write_reg(base + 2, eid8);
    _write_reg(base + 3, eid0);
}

void _MCP2515Base::_set_rx_mode(uint8_t buf, uint8_t mode) {
    uint8_t reg = (buf == 0) ? REG_RXB0CTRL : REG_RXB1CTRL;
    _modify_reg(reg, RXB0CTRL_RXM_MASK, (uint8_t)(mode << 5));
}

void _MCP2515Base::_abort_tx() {
    _modify_reg(REG_CANCTRL, 0x10, 0x10);
    unsigned long start = _millis_impl();
    while ((unsigned long)(_millis_impl() - start) < 500) {
        if (!(_read_reg(REG_CANCTRL) & 0x10)) return;
        _delay_ms(5);
    }
}

void _MCP2515Base::_set_one_shot(bool enable) {
    _modify_reg(REG_CANCTRL, 0x08, enable ? 0x08 : 0x00);
}

void _MCP2515Base::_clear_overflow(uint8_t buf) {
    uint8_t bit = (buf == 0) ? EFLG_RX0OVR : EFLG_RX1OVR;
    _modify_reg(REG_EFLG, bit, 0x00);
}

void MCP2515Minimal::init(uint16_t bitrate_kbps, uint8_t osc_mhz) {
    _bitrate_kbps = bitrate_kbps;
    _osc_mhz = osc_mhz;

    _reset();
    _delay_ms(5);
    _wait_op_mode(CANSTAT_OPMOD_CONFIG);

    uint8_t cnf1, cnf2, cnf3;
    _cnf_for(bitrate_kbps, osc_mhz, cnf1, cnf2, cnf3);
    _write_reg(REG_CNF1, cnf1);
    _write_reg(REG_CNF2, cnf2);
    _write_reg(REG_CNF3, cnf3);

    _write_reg(REG_RXB0CTRL, RXB0CTRL_RXM_ANY | RXB0CTRL_BUKT);
    _write_reg(REG_RXB1CTRL, RXB0CTRL_RXM_ANY);
    _write_reg(REG_CANINTE, 0x00);

    _write_reg(REG_CANCTRL, 0x00);
    _wait_op_mode(CANSTAT_OPMOD_NORMAL);
}

uint8_t MCP2515Minimal::send(uint32_t id, const uint8_t* data, uint8_t len, bool extended) {
    return _send(id, data, len, extended, false, 0xFF);
}

bool MCP2515Minimal::recv(CanFrame& frame, uint32_t timeout_ms) {
    return _poll_rx(frame, timeout_ms);
}

uint8_t MCP2515Full::send_buffered(uint32_t id, const uint8_t* data, uint8_t len,
                                    bool extended, uint8_t buf) {
    if (buf > 2) buf = 0;
    return _send(id, data, len, extended, false, buf);
}

void MCP2515Full::set_filter(uint8_t filter_num, uint32_t id, bool extended) {
    uint8_t prev = _get_mode();
    if (prev != CANSTAT_OPMOD_CONFIG) {
        _set_mode(CANSTAT_OPMOD_CONFIG);
        _set_filter(filter_num, id, extended);
        if (prev != CANSTAT_OPMOD_CONFIG) _set_mode(prev);
    } else {
        _set_filter(filter_num, id, extended);
    }
}

void MCP2515Full::set_mask(uint8_t mask_num, uint32_t mask, bool extended) {
    uint8_t prev = _get_mode();
    if (prev != CANSTAT_OPMOD_CONFIG) {
        _set_mode(CANSTAT_OPMOD_CONFIG);
        _set_mask(mask_num, mask, extended);
        if (prev != CANSTAT_OPMOD_CONFIG) _set_mode(prev);
    } else {
        _set_mask(mask_num, mask, extended);
    }
}

void MCP2515Full::set_rx_mode(uint8_t buf, uint8_t mode) {
    _set_rx_mode(buf, mode);
}

void MCP2515Full::set_mode(uint8_t mode) {
    _set_mode(mode);
}

void MCP2515Full::read_errors(uint8_t& tec, uint8_t& rec, uint8_t& eflg) const {
    tec  = _read_reg(REG_TEC);
    rec  = _read_reg(REG_REC);
    eflg = _read_reg(REG_EFLG);
}
