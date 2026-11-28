#include "St25dv.h"
#include <string.h>

#if defined(__linux__)
#include <unistd.h>
#define DELAY_MS(ms) usleep((ms) * 1000)
#elif defined(__ZEPHYR__)
#include <zephyr/kernel.h>
#define DELAY_MS(ms) k_sleep(K_MSEC(ms))
#elif defined(ESP_PLATFORM)
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
#define DELAY_MS(ms) vTaskDelay(pdMS_TO_TICKS(ms) > 0 ? pdMS_TO_TICKS(ms) : 1)
#elif __has_include(<stm32f4xx_hal.h>)
#include <stm32f4xx_hal.h>
#define DELAY_MS(ms) HAL_Delay(ms)
#elif __has_include(<pico/time.h>)
#include <pico/time.h>
#define DELAY_MS(ms) sleep_ms(ms)
#else
#include <Arduino.h>
#define DELAY_MS(ms) delay(ms)
#endif

constexpr uint8_t ST25DVSource::RF_USER;
constexpr uint8_t ST25DVSource::RF_ACTIVITY;
constexpr uint8_t ST25DVSource::RF_INTERRUPT;
constexpr uint8_t ST25DVSource::FIELD_FALLING;
constexpr uint8_t ST25DVSource::FIELD_RISING;
constexpr uint8_t ST25DVSource::RF_PUT_MSG;
constexpr uint8_t ST25DVSource::RF_GET_MSG;
constexpr uint8_t ST25DVSource::RF_WRITE;
constexpr uint8_t ST25DVFull::GPO_RF_USER;
constexpr uint8_t ST25DVFull::GPO_RF_ACTIVITY;
constexpr uint8_t ST25DVFull::GPO_RF_INTERRUPT;
constexpr uint8_t ST25DVFull::GPO_FIELD_CHANGE;
constexpr uint8_t ST25DVFull::GPO_RF_PUT_MSG;
constexpr uint8_t ST25DVFull::GPO_RF_GET_MSG;
constexpr uint8_t ST25DVFull::GPO_RF_WRITE;
constexpr uint8_t ST25DVFull::GPO_EN;

// --- Minimal ---

ST25DVMinimal::ST25DVMinimal(RegisterConnection& user, RegisterConnection& system, bool gpo_active_high)
    : _user(user), _system(system), _gpo_active_high(gpo_active_high) {}

bool ST25DVMinimal::_read(RegisterConnection& c, uint16_t reg, uint8_t* buf, size_t len) {
    for (uint8_t attempt = 0; attempt < READ_RETRIES; attempt++) {
        if (attempt) DELAY_MS(5);
        c.read(reg, buf, len);
        if (c.ok()) return true;
    }
    return false;
}

bool ST25DVMinimal::_ack_poll(RegisterConnection& c, uint16_t timeout_ms) {
    static const uint8_t none = 0;
    for (uint16_t t = 0;; t++) {
        c.write(0, &none, 0);
        if (c.ok()) return true;
        if (t >= timeout_ms) return false;
        DELAY_MS(1);
    }
}

bool ST25DVMinimal::_in_range(uint32_t address, uint32_t length) const {
    return address + length <= _size;
}

bool ST25DVMinimal::init() {
    uint8_t id[4];
    if (!_read(_system, SYS_MEM_SIZE, id, 4)) return false;
    uint16_t mem = (uint16_t)(id[0] | (id[1] << 8));
    _size = (uint32_t)(mem + 1) * (uint32_t)(id[2] + 1);
    _ic_ref = id[3];
    return true;
}

bool ST25DVMinimal::read(uint16_t address, uint8_t* buf, size_t length) {
    if (!_in_range(address, length)) return false;
    uint32_t a = address;
    size_t done = 0;
    while (done < length) {
        size_t n = CHUNK - (a % CHUNK);
        if (n > length - done) n = length - done;
        if (!_read(_user, (uint16_t)a, buf + done, n)) return false;
        a += n;
        done += n;
    }
    return true;
}

bool ST25DVMinimal::write(uint16_t address, const uint8_t* data, size_t length) {
    if (!_in_range(address, length)) return false;
    uint32_t a = address;
    size_t done = 0;
    while (done < length) {
        size_t n = CHUNK - (a % CHUNK);
        if (n > length - done) n = length - done;
        _user.write((uint16_t)a, data + done, n);
        if (!_user.ok()) return false;
        if (!_ack_poll(_user, WRITE_TIMEOUT_MS)) return false;
        a += n;
        done += n;
    }
    return true;
}

// --- Full ---

ST25DVFull* ST25DVFull::_activeInstance = nullptr;

ST25DVFull::ST25DVFull(RegisterConnection& user, RegisterConnection& system, bool gpo_active_high)
    : ST25DVMinimal(user, system, gpo_active_high) {}

bool ST25DVFull::_read_byte(RegisterConnection& c, uint16_t reg, uint8_t& value) {
    value = 0;
    return _read(c, reg, &value, 1);
}

bool ST25DVFull::_write_dyn(uint16_t reg, uint8_t value) {
    _user.write(reg, &value, 1);
    return _user.ok();
}

bool ST25DVFull::uid(uint8_t out[8]) {
    uint8_t raw[8];
    if (!_read(_system, SYS_UID, raw, 8)) return false;
    for (uint8_t i = 0; i < 8; i++) out[i] = raw[7 - i];
    return true;
}

uint8_t ST25DVFull::ic_revision() {
    uint8_t v;
    _read_byte(_system, SYS_IC_REV, v);
    return v;
}

bool ST25DVFull::read_system(uint16_t address, uint8_t* buf, size_t length) {
    return _read(_system, address, buf, length);
}

bool ST25DVFull::write_system(uint16_t address, const uint8_t* data, size_t length) {
    _system.write(address, data, length);
    if (!_system.ok()) return false;
    return _ack_poll(_system, WRITE_TIMEOUT_MS);
}

bool ST25DVFull::write_system(uint16_t address, uint8_t value) {
    return write_system(address, &value, 1);
}

bool ST25DVFull::_send_password(const uint8_t password[8], uint8_t code) {
    uint8_t frame[17];
    memcpy(frame, password, 8);
    frame[8] = code;
    memcpy(frame + 9, password, 8);
    _system.write(SYS_I2C_PWD, frame, sizeof(frame));
    return _system.ok();
}

bool ST25DVFull::present_password(const uint8_t password[8]) {
    _send_password(password, 0x09);
    _ack_poll(_system, WRITE_TIMEOUT_MS);
    return session_open();
}

bool ST25DVFull::write_password(const uint8_t password[8]) {
    if (!session_open()) return false;
    if (!_send_password(password, 0x07)) return false;
    return _ack_poll(_system, WRITE_TIMEOUT_MS);
}

bool ST25DVFull::session_open() {
    uint8_t v;
    if (!_read_byte(_user, DYN_I2C_SSO, v)) return false;
    return (v & 0x01) != 0;
}

bool ST25DVFull::set_area_end(uint8_t area, uint8_t end_block) {
    if (area < 1 || area > 3) return false;
    return write_system((uint16_t)(SYS_ENDA1 + 2 * (area - 1)), end_block);
}

bool ST25DVFull::set_area_protection(uint8_t area, uint8_t rf, uint8_t i2c) {
    if (area < 1 || area > 4 || rf > 0x0F || i2c > 3) return false;
    if (!write_system((uint16_t)(SYS_RFA1SS + 2 * (area - 1)), rf)) return false;
    uint8_t i2css;
    if (!_read_byte(_system, SYS_I2CSS, i2css)) return false;
    uint8_t shift = (uint8_t)(2 * (area - 1));
    i2css = (uint8_t)((i2css & ~(0x03 << shift)) | (i2c << shift));
    return write_system(SYS_I2CSS, i2css);
}

bool ST25DVFull::get_area_protection(uint8_t area, uint8_t& rf, uint8_t& i2c) {
    if (area < 1 || area > 4) return false;
    uint8_t r, s;
    if (!_read_byte(_system, (uint16_t)(SYS_RFA1SS + 2 * (area - 1)), r)) return false;
    if (!_read_byte(_system, SYS_I2CSS, s)) return false;
    rf = r & 0x0F;
    i2c = (uint8_t)((s >> (2 * (area - 1))) & 0x03);
    return true;
}

bool ST25DVFull::set_gpo(uint8_t sources, bool enable) {
    return write_system(SYS_GPO, (uint8_t)((sources & 0x7F) | (enable ? GPO_EN : 0)));
}

uint8_t ST25DVFull::get_gpo() {
    uint8_t v;
    _read_byte(_system, SYS_GPO, v);
    return v;
}

bool ST25DVFull::set_gpo_runtime(bool enable) {
    return _write_dyn(DYN_GPO_CTRL, enable ? GPO_EN : 0);
}

bool ST25DVFull::set_interrupt_pulse(uint8_t value) {
    if (value > 7) return false;
    return write_system(SYS_IT_TIME, value);
}

bool ST25DVFull::field_present() {
    uint8_t v;
    if (!_read_byte(_user, DYN_EH_CTRL, v)) return false;
    return (v & 0x04) != 0;
}

bool ST25DVFull::set_energy_harvesting(bool enabled, bool forced) {
    uint8_t mode = forced ? 0 : 1;
    uint8_t cur;
    if (!_read_byte(_system, SYS_EH_MODE, cur)) return false;
    if ((cur & 0x01) != mode && !write_system(SYS_EH_MODE, mode)) return false;
    return _write_dyn(DYN_EH_CTRL, enabled ? 0x01 : 0x00);
}

bool ST25DVFull::set_rf_state(bool disable, bool sleep) {
    return _write_dyn(DYN_RF_MNGT, (uint8_t)((disable ? 0x01 : 0) | (sleep ? 0x02 : 0)));
}

bool ST25DVFull::enable_mailbox() {
    uint8_t mode;
    if (!_read_byte(_system, SYS_MB_MODE, mode)) return false;
    if (!(mode & 0x01) && !write_system(SYS_MB_MODE, 0x01)) return false;
    return _write_dyn(DYN_MB_CTRL, 0x01);
}

bool ST25DVFull::disable_mailbox() {
    return _write_dyn(DYN_MB_CTRL, 0x00);
}

ST25DVMailboxStatus ST25DVFull::mailbox_status() {
    uint8_t v;
    _read_byte(_user, DYN_MB_CTRL, v);
    ST25DVMailboxStatus s;
    s.enabled = (v & 0x01) != 0;
    s.host_put = (v & 0x02) != 0;
    s.rf_put = (v & 0x04) != 0;
    s.host_miss = (v & 0x10) != 0;
    s.rf_miss = (v & 0x20) != 0;
    s.host_current = (v & 0x40) != 0;
    s.rf_current = (v & 0x80) != 0;
    return s;
}

bool ST25DVFull::mailbox_write(const uint8_t* data, size_t length) {
    if (length < 1 || length > MAILBOX_SIZE) return false;
    uint8_t ctrl;
    if (!_read_byte(_user, DYN_MB_CTRL, ctrl)) return false;
    if (!(ctrl & 0x01) || (ctrl & 0x06)) return false;
    // One transaction of up to 258 bytes: RegisterConnection::write() caps data at 32 bytes.
    uint8_t frame[2 + MAILBOX_SIZE];
    frame[0] = (uint8_t)(MAILBOX >> 8);
    frame[1] = (uint8_t)(MAILBOX & 0xFF);
    memcpy(frame + 2, data, length);
    static_cast<Connection&>(_user).write(frame, length + 2);
    return _user.ok();
}

size_t ST25DVFull::mailbox_read(uint8_t* buf, size_t capacity) {
    uint8_t ctrl, len;
    if (!_read_byte(_user, DYN_MB_CTRL, ctrl)) return 0;
    if (!(ctrl & 0x06)) return 0;
    if (!_read_byte(_user, DYN_MB_LEN, len)) return 0;
    size_t length = (size_t)len + 1;
    if (length > capacity) return 0;
    size_t done = 0;
    while (done < length) {
        size_t n = length - done;
        if (n > CHUNK) n = CHUNK;
        if (!_read(_user, (uint16_t)(MAILBOX + done), buf + done, n)) return 0;
        done += n;
    }
    return length;
}

bool ST25DVFull::set_mailbox_watchdog(uint8_t value) {
    if (value > 7) return false;
    return write_system(SYS_MB_WDG, value);
}

bool ST25DVFull::lock_ccfile(bool lock) {
    return write_system(SYS_LOCK_CCFILE, lock ? 0x03 : 0x00);
}

bool ST25DVFull::write_ndef(const uint8_t* message, size_t length) {
    uint8_t head[12];
    size_t h = 0;
    if (_size <= 2048) {
        uint32_t mlen = _size / 8;
        if (mlen > 0xFF) mlen = 0xFF;
        head[h++] = 0xE1; head[h++] = 0x40; head[h++] = (uint8_t)mlen; head[h++] = 0x01;
    } else {
        uint32_t mlen = _size / 8 - 1;
        head[h++] = 0xE2; head[h++] = 0x40; head[h++] = 0x00; head[h++] = 0x01;
        head[h++] = 0x00; head[h++] = 0x00;
        head[h++] = (uint8_t)(mlen >> 8); head[h++] = (uint8_t)(mlen & 0xFF);
    }
    head[h++] = 0x03;
    if (length < 0xFF) {
        head[h++] = (uint8_t)length;
    } else {
        head[h++] = 0xFF;
        head[h++] = (uint8_t)(length >> 8);
        head[h++] = (uint8_t)(length & 0xFF);
    }
    if (h + length + 1 > _size) return false;
    if (!write(0, head, h)) return false;
    if (length && !write((uint16_t)h, message, length)) return false;
    static const uint8_t terminator = 0xFE;
    return write((uint16_t)(h + length), &terminator, 1);
}

size_t ST25DVFull::read_ndef(uint8_t* buf, size_t capacity) {
    uint8_t cc[4];
    if (!read(0, cc, 4)) return 0;
    uint32_t pos;
    if (cc[0] == 0xE1) pos = 4;
    else if (cc[0] == 0xE2) pos = 8;
    else return 0;
    while (pos < _size) {
        uint8_t t;
        if (!read((uint16_t)pos, &t, 1)) return 0;
        if (t == 0x00) { pos++; continue; }
        if (t == 0xFE || pos + 2 > _size) return 0;
        uint8_t l;
        if (!read((uint16_t)(pos + 1), &l, 1)) return 0;
        pos += 2;
        uint32_t length = l;
        if (l == 0xFF) {
            uint8_t lb[2];
            if (pos + 2 > _size || !read((uint16_t)pos, lb, 2)) return 0;
            length = ((uint32_t)lb[0] << 8) | lb[1];
            pos += 2;
        }
        if (t == 0x03) {
            if (pos + length > _size || length > capacity) return 0;
            if (length && !read((uint16_t)pos, buf, length)) return 0;
            return length;
        }
        pos += length;
    }
    return 0;
}

bool ST25DVFull::wait_write_complete() {
    return _ack_poll(_user, WRITE_TIMEOUT_MS);
}

uint8_t ST25DVFull::_source_to_gpo(uint8_t source) {
    uint8_t gpo = source & 0x07;
    if (source & (ST25DVSource::FIELD_RISING | ST25DVSource::FIELD_FALLING)) gpo |= 0x08;
    gpo |= (uint8_t)((source >> 1) & 0x70);
    return gpo;
}

bool ST25DVFull::enable_interrupt(uint8_t source) {
    uint8_t gpo;
    if (!_read_byte(_system, SYS_GPO, gpo)) return false;
    gpo = (uint8_t)(gpo | _source_to_gpo(source) | GPO_EN);
    if (!write_system(SYS_GPO, gpo)) return false;
    return set_gpo_runtime(true);
}

bool ST25DVFull::disable_interrupt(uint8_t source) {
    uint8_t gpo;
    if (!_read_byte(_system, SYS_GPO, gpo)) return false;
    return write_system(SYS_GPO, (uint8_t)(gpo & ~_source_to_gpo(source)));
}

uint8_t ST25DVFull::poll_interrupt() {
    uint8_t v;
    if (!_read_byte(_user, DYN_IT_STS, v)) return 0;
    return v;
}

void ST25DVFull::on_interrupt(void (*callback)(uint8_t), InputPin* intPin) {
    off_interrupt();
    InputPin* p = intPin ? intPin : _user.intPin();
    if (!p) return;  // no GPO line wired: the caller polls poll_interrupt()
    _callback = callback;
    _intPinUsed = p;
    _activeInstance = this;
    p->onEdge(&ST25DVFull::_edgeTrampoline, _gpo_active_high ? InputPin::kRising : InputPin::kFalling);
}

void ST25DVFull::off_interrupt() {
    if (_intPinUsed) {
        _intPinUsed->offEdge(&ST25DVFull::_edgeTrampoline);
        _intPinUsed = nullptr;
    }
    _callback = nullptr;
    if (_activeInstance == this) _activeInstance = nullptr;
}

void ST25DVFull::_edgeTrampoline() {
    if (_activeInstance) _activeInstance->_handleEdge();
}

void ST25DVFull::_handleEdge() {
    uint8_t s = poll_interrupt();
    if (s && _callback) _callback(s);
}
