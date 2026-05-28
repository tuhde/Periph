#include "APDS9960.h"

#ifdef __linux__
#include <unistd.h>
#define DELAY_MS(ms) usleep((ms) * 1000)
#else
#include <Arduino.h>
#define DELAY_MS(ms) delay(ms)
#endif

#ifdef CONFIG_ZEPHYR
#include <zephyr/kernel.h>
#undef DELAY_MS
#define DELAY_MS(ms) k_sleep(K_MSEC(ms))
#endif

APDS9960Minimal::APDS9960Minimal(Transport& transport)
    : _transport(transport) {
    DELAY_MS(6);
    uint8_t id = _read_reg(REG_ID);
    (void)id;
    _write_reg(REG_ENABLE, 0x00);
    _write_reg(REG_ATIME, ATIME_DEFAULT);
    _write_reg(REG_CONTROL, CONTROL_DEFAULT);
    _write_reg(REG_CONFIG2, CONFIG2_DEFAULT);
    _write_reg(REG_ENABLE, 0x03);
    DELAY_MS(210);
}

void APDS9960Minimal::_write_reg(uint8_t reg, uint8_t value) {
    uint8_t buf[2] = { reg, value };
    _transport.write(buf, 2);
}

uint8_t APDS9960Minimal::_read_reg(uint8_t reg) {
    uint8_t val;
    _transport.write_read(&reg, 1, &val, 1);
    return val;
}

uint16_t APDS9960Minimal::_read_reg16_le(uint8_t reg) {
    uint8_t buf[2];
    _transport.write_read(&reg, 1, buf, 2);
    return (uint16_t)buf[0] | ((uint16_t)buf[1] << 8);
}

uint16_t APDS9960Minimal::color_clear() {
    return _read_reg16_le(REG_CDATAL);
}

uint16_t APDS9960Minimal::color_red() {
    return _read_reg16_le(REG_RDATAL);
}

uint16_t APDS9960Minimal::color_green() {
    return _read_reg16_le(REG_GDATAL);
}

uint16_t APDS9960Minimal::color_blue() {
    return _read_reg16_le(REG_BDATAL);
}

void APDS9960Minimal::color(uint16_t& clear, uint16_t& red, uint16_t& green, uint16_t& blue) {
    uint8_t reg = REG_CDATAL;
    uint8_t buf[8];
    _transport.write_read(&reg, 1, buf, 8);
    clear = (uint16_t)buf[0] | ((uint16_t)buf[1] << 8);
    red   = (uint16_t)buf[2] | ((uint16_t)buf[3] << 8);
    green = (uint16_t)buf[4] | ((uint16_t)buf[5] << 8);
    blue  = (uint16_t)buf[6] | ((uint16_t)buf[7] << 8);
}

// APDS9960Full

APDS9960Full::APDS9960Full(Transport& transport)
    : APDS9960Minimal(transport) {}

void APDS9960Full::enable_proximity(bool enabled) {
    uint8_t val = _read_reg(REG_ENABLE);
    if (enabled) val |= 0x04;
    else         val &= ~0x04;
    _write_reg(REG_ENABLE, val);
}

uint8_t APDS9960Full::proximity() {
    return _read_reg(REG_PDATA);
}

void APDS9960Full::enable_wait(bool enabled) {
    uint8_t val = _read_reg(REG_ENABLE);
    if (enabled) val |= 0x08;
    else         val &= ~0x08;
    _write_reg(REG_ENABLE, val);
}

void APDS9960Full::configure_wait(uint8_t wtime, bool wlong) {
    _write_reg(REG_WTIME, wtime);
    uint8_t c1 = _read_reg(REG_CONFIG1);
    if (wlong) c1 |= 0x02;
    else       c1 &= ~0x02;
    c1 = (c1 & 0x03) | 0x60;
    _write_reg(REG_CONFIG1, c1);
}

void APDS9960Full::configure_als(uint8_t atime, uint8_t again) {
    _write_reg(REG_ATIME, atime);
    uint8_t ctrl = _read_reg(REG_CONTROL);
    ctrl = (ctrl & 0xFC) | (again & 0x03);
    _write_reg(REG_CONTROL, ctrl);
}

void APDS9960Full::configure_proximity_led(uint8_t ldrive, uint8_t pgain, uint8_t ppulse, uint8_t pplen) {
    uint8_t ctrl = _read_reg(REG_CONTROL);
    ctrl = ((ldrive & 0x03) << 6) | ((pgain & 0x03) << 2) | (ctrl & 0x03);
    _write_reg(REG_CONTROL, ctrl);
    _write_reg(REG_PPULSE, ((pplen & 0x03) << 6) | (ppulse & 0x3F));
}

void APDS9960Full::set_led_boost(uint8_t boost) {
    uint8_t c2 = _read_reg(REG_CONFIG2);
    c2 = (c2 & 0xCF) | ((boost & 0x03) << 4) | 0x01;
    _write_reg(REG_CONFIG2, c2);
}

void APDS9960Full::als_threshold(uint16_t low, uint16_t high) {
    _write_reg(REG_AILTL, low & 0xFF);
    _write_reg(REG_AILTH, (low >> 8) & 0xFF);
    _write_reg(REG_AIHTL, high & 0xFF);
    _write_reg(REG_AIHTH, (high >> 8) & 0xFF);
}

void APDS9960Full::proximity_threshold(uint8_t low, uint8_t high) {
    _write_reg(REG_PILT, low);
    _write_reg(REG_PIHT, high);
}

void APDS9960Full::set_persistence(uint8_t ppers, uint8_t apers) {
    _write_reg(REG_PERS, ((ppers & 0x0F) << 4) | (apers & 0x0F));
}

void APDS9960Full::enable_als_interrupt(bool enabled) {
    uint8_t val = _read_reg(REG_ENABLE);
    if (enabled) val |= 0x10;
    else         val &= ~0x10;
    _write_reg(REG_ENABLE, val);
}

void APDS9960Full::enable_proximity_interrupt(bool enabled) {
    uint8_t val = _read_reg(REG_ENABLE);
    if (enabled) val |= 0x20;
    else         val &= ~0x20;
    _write_reg(REG_ENABLE, val);
}

void APDS9960Full::clear_proximity_interrupt() {
    uint8_t reg = REG_PICLEAR;
    _transport.write(&reg, 1);
}

void APDS9960Full::clear_als_interrupt() {
    uint8_t reg = REG_CICLEAR;
    _transport.write(&reg, 1);
}

void APDS9960Full::clear_all_interrupts() {
    uint8_t reg = REG_AICLEAR;
    _transport.write(&reg, 1);
}

void APDS9960Full::set_proximity_offset(int8_t ur, int8_t dl) {
    auto encode = [](int8_t v) -> uint8_t {
        if (v < 0) return 0x80 | ((-v) & 0x7F);
        return v & 0x7F;
    };
    _write_reg(REG_POFFSET_UR, encode(ur));
    _write_reg(REG_POFFSET_DL, encode(dl));
}

void APDS9960Full::set_proximity_mask(bool u, bool d, bool l, bool r) {
    uint8_t c3 = _read_reg(REG_CONFIG3) & 0xF0;
    if (u) c3 |= 0x08;
    if (d) c3 |= 0x04;
    if (l) c3 |= 0x02;
    if (r) c3 |= 0x01;
    _write_reg(REG_CONFIG3, c3);
}

void APDS9960Full::enable_gesture(bool enabled) {
    uint8_t val = _read_reg(REG_ENABLE);
    if (enabled) {
        val |= 0x40;
        _write_reg(REG_ENABLE, val);
        uint8_t g4 = _read_reg(REG_GCONF4);
        g4 |= 0x01;
        _write_reg(REG_GCONF4, g4);
    } else {
        val &= ~0x40;
        _write_reg(REG_ENABLE, val);
        uint8_t g4 = _read_reg(REG_GCONF4);
        g4 &= ~0x01;
        _write_reg(REG_GCONF4, g4);
    }
}

void APDS9960Full::configure_gesture(uint8_t ggain, uint8_t gldrive, uint8_t gpulse,
                                      uint8_t gplen, uint8_t gwtime, uint8_t gpenth, uint8_t gexth) {
    _write_reg(REG_GPENTH, gpenth);
    _write_reg(REG_GEXTH, gexth);
    uint8_t g2 = ((ggain & 0x03) << 5) | ((gldrive & 0x03) << 3) | (gwtime & 0x07);
    _write_reg(REG_GCONF2, g2);
    _write_reg(REG_GPULSE, ((gplen & 0x03) << 6) | (gpulse & 0x3F));
}

bool APDS9960Full::gesture_available() {
    return (_read_reg(REG_GSTATUS) & 0x01) != 0;
}

uint8_t APDS9960Full::read_gesture_fifo(uint8_t* buf, uint8_t max_len) {
    uint8_t level = _read_reg(REG_GFLVL);
    if (level == 0 || level > max_len) level = max_len;
    uint8_t reg = REG_GFIFO_U;
    for (uint8_t i = 0; i < level; i++) {
        _transport.write_read(&reg, 1, buf + i * 4, 4);
    }
    return level;
}

uint8_t APDS9960Full::gesture_fifo_level() {
    return _read_reg(REG_GFLVL);
}

void APDS9960Full::clear_gesture_fifo() {
    uint8_t g4 = _read_reg(REG_GCONF4);
    g4 |= 0x04;
    _write_reg(REG_GCONF4, g4);
}

void APDS9960Full::enable_gesture_interrupt(bool enabled) {
    uint8_t g4 = _read_reg(REG_GCONF4);
    if (enabled) g4 |= 0x02;
    else         g4 &= ~0x02;
    _write_reg(REG_GCONF4, g4);
}

uint8_t APDS9960Full::status() {
    return _read_reg(REG_STATUS);
}

bool APDS9960Full::is_als_valid() {
    return (_read_reg(REG_STATUS) & 0x01) != 0;
}

bool APDS9960Full::is_proximity_valid() {
    return (_read_reg(REG_STATUS) & 0x02) != 0;
}

bool APDS9960Full::is_als_saturated() {
    return (_read_reg(REG_STATUS) & 0x80) != 0;
}

bool APDS9960Full::is_proximity_saturated() {
    return (_read_reg(REG_STATUS) & 0x40) != 0;
}

uint8_t APDS9960Full::chip_id() {
    return _read_reg(REG_ID);
}
