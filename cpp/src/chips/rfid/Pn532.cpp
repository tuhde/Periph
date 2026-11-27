#include "Pn532.h"
#include <string.h>

#ifdef __linux__
#include <unistd.h>
static void periph_delay_ms(unsigned ms) { usleep(ms * 1000); }
#elif defined(__ZEPHYR__)
#include <zephyr/kernel.h>
static void periph_delay_ms(unsigned ms) { k_sleep(K_MSEC(ms)); }
#elif defined(ESP_PLATFORM)
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
static void periph_delay_ms(unsigned ms) { vTaskDelay(pdMS_TO_TICKS(ms) > 0 ? pdMS_TO_TICKS(ms) : 1); }
#elif __has_include(<stm32f4xx_hal.h>)
#include <stm32f4xx_hal.h>
static void periph_delay_ms(unsigned ms) { HAL_Delay(ms); }
#elif __has_include(<pico/time.h>)
#include <pico/time.h>
static void periph_delay_ms(unsigned ms) { sleep_ms(ms); }
#else
#include <Arduino.h>
static void periph_delay_ms(unsigned ms) { delay(ms); }
#endif

static const uint8_t PN532_ACK[6] = {0x00, 0x00, 0xFF, 0x00, 0xFF, 0x00};
static const uint8_t PN532_NACK[6] = {0x00, 0x00, 0xFF, 0xFF, 0x00, 0x00};
static const uint8_t PN532_PATTERN[8] = {'P', 'E', 'R', 'I', 'P', 'H', '0', '1'};

// --- Minimal ---

PN532Minimal::PN532Minimal(Connection& connection) : _connection(connection) {}

bool PN532Minimal::_write(const uint8_t* data, size_t len) {
    for (unsigned attempt = 0; attempt < WRITE_RETRIES; attempt++) {
        _connection.write(data, len);
        if (_connection.ok()) return true;
        periph_delay_ms(2);
    }
    _lastError = ERR_BUS;
    return false;
}

bool PN532Minimal::_ready() {
    uint8_t s = 0;
    _connection.read(&s, 1);
    return _connection.ok() && (s & 0x01);
}

bool PN532Minimal::_wait(uint32_t timeoutMs) {
    for (uint32_t i = 0; i <= timeoutMs; i++) {
        if (_ready()) return true;
        periph_delay_ms(1);
    }
    return false;
}

bool PN532Minimal::_start(uint8_t command, const uint8_t* data, size_t len) {
    if (len > MAX_DATA) { _lastError = ERR_ARG; return false; }
    uint8_t frame[MAX_DATA + 9];
    uint8_t length = (uint8_t)(len + 2);
    frame[0] = 0x00; frame[1] = 0x00; frame[2] = 0xFF;
    frame[3] = length;
    frame[4] = (uint8_t)(0x100 - length);
    frame[5] = TFI_HOST;
    frame[6] = command;
    uint8_t sum = (uint8_t)(TFI_HOST + command);
    for (size_t i = 0; i < len; i++) { frame[7 + i] = data[i]; sum = (uint8_t)(sum + data[i]); }
    frame[7 + len] = (uint8_t)(0x100 - sum);
    frame[8 + len] = 0x00;
    for (unsigned attempt = 0; attempt < ACK_ATTEMPTS; attempt++) {
        if (!_write(frame, len + 9)) return false;
        if (_wait(ACK_TIMEOUT_MS)) {
            uint8_t ack[7];
            _connection.read(ack, 7);
            if (_connection.ok() && memcmp(ack + 1, PN532_ACK, 6) == 0) return true;
        }
    }
    _lastError = ERR_NO_ACK;
    return false;
}

int PN532Minimal::_response(uint8_t command, uint8_t* out, size_t outMax, uint32_t timeoutMs, bool noneOnTimeout) {
    if (!_wait(timeoutMs)) {
        _write(PN532_ACK, 6);  // abort the running command
        if (noneOnTimeout) return -2;
        _lastError = ERR_TIMEOUT;
        return -1;
    }
    uint8_t header[6];
    _connection.read(header, 6);
    if (!_connection.ok()) { _lastError = ERR_BUS; return -1; }
    if (header[1] != 0x00 || header[2] != 0x00 || header[3] != 0xFF) { _lastError = ERR_FRAME; return -1; }
    uint8_t length = header[4];
    if ((uint8_t)(length + header[5]) != 0) { _lastError = ERR_FRAME; return -1; }
    if (length > MAX_DATA + 2) { _lastError = ERR_FRAME; return -1; }
    if (!_write(PN532_NACK, 6)) return -1;  // have the chip resend the whole frame
    if (!_wait(timeoutMs)) { _lastError = ERR_TIMEOUT; return -1; }
    size_t total = 1 + (size_t)length + 7;
    _connection.read(_buf, total);
    if (!_connection.ok()) { _lastError = ERR_BUS; return -1; }
    const uint8_t* body = _buf + 6;
    if (length == 1 && body[0] == TFI_ERROR) { _lastError = ERR_ERROR_FRAME; return -1; }
    if (length < 2 || body[0] != TFI_CHIP || body[1] != (uint8_t)(command + 1)) { _lastError = ERR_FRAME; return -1; }
    uint8_t sum = 0;
    for (size_t i = 0; i <= length; i++) sum = (uint8_t)(sum + body[i]);
    if (sum != 0) { _lastError = ERR_FRAME; return -1; }
    size_t n = (size_t)length - 2;
    if (n > outMax) n = outMax;
    if (out && n) memcpy(out, body + 2, n);
    _lastError = 0;
    return (int)n;
}

int PN532Minimal::_command(uint8_t command, const uint8_t* data, size_t len, uint8_t* out, size_t outMax,
                           uint32_t timeoutMs) {
    if (!_start(command, data, len)) return -1;
    return _response(command, out, outMax, timeoutMs);
}

int PN532Minimal::_list(uint8_t maxTargets, uint8_t baud, const uint8_t* initiatorData, size_t initLen,
                        uint8_t* out, size_t outMax, uint32_t timeoutMs) {
    if (initLen > MAX_DATA - 2) { _lastError = ERR_ARG; return -1; }
    uint8_t payload[MAX_DATA];
    payload[0] = maxTargets;
    payload[1] = baud;
    if (initLen) memcpy(payload + 2, initiatorData, initLen);
    if (!_start(CMD_IN_LIST_PASSIVE_TARGET, payload, initLen + 2)) return -1;
    return _response(CMD_IN_LIST_PASSIVE_TARGET, out, outMax, timeoutMs, true);
}

bool PN532Minimal::init() {
    uint8_t d[4];
    int n = _command(CMD_GET_FIRMWARE_VERSION, nullptr, 0, d, sizeof(d));
    if (n < 0) return false;
    if (n < 4 || d[0] != IC) { _lastError = ERR_IC; return false; }
    static const uint8_t sam[3] = {0x01, 0x14, 0x01};
    if (_command(CMD_SAM_CONFIGURATION, sam, 3, nullptr, 0) < 0) return false;
    static const uint8_t retries[4] = {0x05, 0xFF, 0x01, 0x02};
    return _command(CMD_RF_CONFIGURATION, retries, 4, nullptr, 0) >= 0;
}

uint8_t PN532Minimal::read_uid(uint8_t* uid, uint32_t timeoutMs) {
    uint8_t d[MAX_DATA];
    int n = _list(1, 0x00, nullptr, 0, d, sizeof(d), timeoutMs);
    if (n == -2) { _lastError = 0; return 0; }
    if (n < 6 || d[0] == 0) return 0;
    uint8_t len = d[5];
    if (len > 10 || (size_t)(6 + len) > (size_t)n) { _lastError = ERR_FRAME; return 0; }
    memcpy(uid, d + 6, len);
    if (_command(CMD_IN_RELEASE, &d[1], 1, nullptr, 0) < 0) return 0;
    return len;
}

// --- Full ---

PN532Full* PN532Full::_activeInstance = nullptr;

PN532Full::PN532Full(Connection& connection) : PN532Minimal(connection) {}

bool PN532Full::firmware_version(PN532FirmwareVersion& fw) {
    uint8_t d[4];
    if (_command(CMD_GET_FIRMWARE_VERSION, nullptr, 0, d, sizeof(d)) < 4) {
        if (_lastError == 0) _lastError = ERR_FRAME;
        return false;
    }
    fw.ic = d[0]; fw.version = d[1]; fw.revision = d[2]; fw.support = d[3];
    fw.iso14443a = (d[3] & 0x01) != 0;
    fw.iso14443b = (d[3] & 0x02) != 0;
    fw.iso18092 = (d[3] & 0x04) != 0;
    return true;
}

bool PN532Full::general_status(PN532GeneralStatus& status) {
    uint8_t d[MAX_DATA];
    int n = _command(CMD_GET_GENERAL_STATUS, nullptr, 0, d, sizeof(d));
    if (n < 3) {
        if (_lastError == 0) _lastError = ERR_FRAME;
        return false;
    }
    status.error = d[0];
    status.field = d[1] != 0;
    status.targets = d[2];
    int samIndex = 3 + 4 * d[2];
    status.sam_status = samIndex < n ? d[samIndex] : 0;
    return true;
}

int PN532Full::list_passive_targets(PN532Target* targets, uint8_t baud, uint8_t maxTargets,
                                    const uint8_t* initiatorData, size_t initLen, uint32_t timeoutMs) {
    if (maxTargets < 1 || maxTargets > 2 || baud > 4) { _lastError = ERR_ARG; return -1; }
    uint8_t d[MAX_DATA];
    int n = _list(maxTargets, baud, initiatorData, initLen, d, sizeof(d), timeoutMs);
    if (n == -2) { _lastError = 0; return 0; }
    if (n < 0) return -1;
    if (n == 0) return 0;
    int count = 0;
    int i = 1;
    for (int k = 0; k < d[0] && k < maxTargets; k++) {
        if (i >= n) break;
        PN532Target& t = targets[count];
        memset(&t, 0, sizeof(t));
        t.tg = d[i];
        if (baud == BAUD_106_A) {
            if (i + 4 >= n) break;
            t.sens_res = (uint16_t)((d[i + 1] << 8) | d[i + 2]);
            t.sel_res = d[i + 3];
            uint8_t len = d[i + 4];
            if (len > 10 || i + 5 + len > n) break;
            memcpy(t.uid, d + i + 5, len);
            t.uid_len = len;
            i += 5 + len;
            if ((t.sel_res & 0x20) && i < n) {
                uint8_t tl = d[i];
                uint8_t copy = tl;
                if (i + copy > n) copy = (uint8_t)(n - i);
                if (copy > sizeof(t.ats)) copy = sizeof(t.ats);
                memcpy(t.ats, d + i, copy);
                t.ats_len = copy;
                i += tl;
            }
        } else if (baud == BAUD_212_F || baud == BAUD_424_F) {
            if (i + 1 >= n) break;
            uint8_t len = d[i + 1];  // POL_RES length incl. itself
            if (len < 10 || i + 1 + len > n) break;
            memcpy(t.uid, d + i + 3, 8);
            t.uid_len = 8;
            uint8_t rest = (uint8_t)(len - 10);
            if (rest > sizeof(t.ats)) rest = sizeof(t.ats);
            memcpy(t.ats, d + i + 11, rest);
            t.ats_len = rest;
            i += 1 + len;
        } else {
            if (i + 13 >= n) break;
            memcpy(t.uid, d + i + 2, 4);
            t.uid_len = 4;
            uint8_t len = d[i + 13];
            if (i + 14 + len > n) break;
            uint8_t copy = len > sizeof(t.ats) ? (uint8_t)sizeof(t.ats) : len;
            memcpy(t.ats, d + i + 14, copy);
            t.ats_len = copy;
            i += 14 + len;
        }
        count++;
    }
    return count;
}

int PN532Full::_status_command(uint8_t command, const uint8_t* data, size_t len, uint8_t* out, size_t outMax) {
    uint8_t d[MAX_DATA];
    int n = _command(command, data, len, d, sizeof(d));
    if (n < 0) return -1;
    if (n < 1) { _lastError = ERR_FRAME; return -1; }
    if (d[0] & 0x3F) { _lastError = d[0] & 0x3F; return -1; }
    size_t m = (size_t)n - 1;
    if (m > outMax) m = outMax;
    if (out && m) memcpy(out, d + 1, m);
    return (int)m;
}

int PN532Full::data_exchange(uint8_t target, const uint8_t* data, size_t len, uint8_t* out, size_t outMax) {
    if (len > MAX_DATA - 1) { _lastError = ERR_ARG; return -1; }
    uint8_t payload[MAX_DATA];
    payload[0] = target;
    if (len) memcpy(payload + 1, data, len);
    return _status_command(CMD_IN_DATA_EXCHANGE, payload, len + 1, out, outMax);
}

int PN532Full::communicate_thru(const uint8_t* data, size_t len, uint8_t* out, size_t outMax) {
    return _status_command(CMD_IN_COMMUNICATE_THRU, data, len, out, outMax);
}

bool PN532Full::release(uint8_t target) {
    return _status_command(CMD_IN_RELEASE, &target, 1, nullptr, 0) >= 0;
}

bool PN532Full::select(uint8_t target) {
    return _status_command(CMD_IN_SELECT, &target, 1, nullptr, 0) >= 0;
}

bool PN532Full::deselect(uint8_t target) {
    return _status_command(CMD_IN_DESELECT, &target, 1, nullptr, 0) >= 0;
}

bool PN532Full::mifare_authenticate(uint8_t target, uint8_t block, const uint8_t* key, const uint8_t* uid,
                                    size_t uidLen, bool keyB) {
    if (uidLen < 4) { _lastError = ERR_ARG; return false; }
    uint8_t d[12];
    d[0] = keyB ? 0x61 : 0x60;
    d[1] = block;
    memcpy(d + 2, key, 6);
    memcpy(d + 8, uid + uidLen - 4, 4);
    return data_exchange(target, d, sizeof(d), nullptr, 0) >= 0;
}

bool PN532Full::mifare_read_block(uint8_t target, uint8_t block, uint8_t* out) {
    uint8_t d[2] = {0x30, block};
    int n = data_exchange(target, d, 2, out, 16);
    if (n < 0) return false;
    if (n < 16) { _lastError = ERR_FRAME; return false; }
    return true;
}

bool PN532Full::mifare_write_block(uint8_t target, uint8_t block, const uint8_t* data) {
    uint8_t d[18];
    d[0] = 0xA0;
    d[1] = block;
    memcpy(d + 2, data, 16);
    return data_exchange(target, d, sizeof(d), nullptr, 0) >= 0;
}

bool PN532Full::ntag_read_page(uint8_t target, uint8_t page, uint8_t* out) {
    return mifare_read_block(target, page, out);
}

bool PN532Full::ntag_write_page(uint8_t target, uint8_t page, const uint8_t* data) {
    uint8_t d[6];
    d[0] = 0xA2;
    d[1] = page;
    memcpy(d + 2, data, 4);
    return data_exchange(target, d, sizeof(d), nullptr, 0) >= 0;
}

bool PN532Full::set_rf_field(bool on, bool autoRfca) {
    uint8_t d[2] = {0x01, (uint8_t)((on ? 0x02 : 0x00) | (autoRfca ? 0x01 : 0x00))};
    return _command(CMD_RF_CONFIGURATION, d, 2, nullptr, 0) >= 0;
}

bool PN532Full::set_max_retries(uint8_t passiveActivation, uint8_t atr, uint8_t psl) {
    uint8_t d[4] = {0x05, atr, psl, passiveActivation};
    return _command(CMD_RF_CONFIGURATION, d, 4, nullptr, 0) >= 0;
}

bool PN532Full::read_register(uint16_t address, uint8_t& value) {
    uint8_t d[2] = {(uint8_t)(address >> 8), (uint8_t)address};
    int n = _command(CMD_READ_REGISTER, d, 2, &value, 1);
    if (n < 0) return false;
    if (n < 1) { _lastError = ERR_FRAME; return false; }
    return true;
}

bool PN532Full::write_register(uint16_t address, uint8_t value) {
    uint8_t d[3] = {(uint8_t)(address >> 8), (uint8_t)address, value};
    return _command(CMD_WRITE_REGISTER, d, 3, nullptr, 0) >= 0;
}

bool PN532Full::power_down(uint8_t wakeupSources) {
    return _status_command(CMD_POWER_DOWN, &wakeupSources, 1, nullptr, 0) >= 0;
}

bool PN532Full::self_test(uint8_t test) {
    uint8_t d[MAX_DATA];
    if (test == 0x00) {
        uint8_t req[9];
        req[0] = 0x00;
        memcpy(req + 1, PN532_PATTERN, 8);
        int n = _command(CMD_DIAGNOSE, req, 9, d, sizeof(d));
        return n == 9 && memcmp(d, req, 9) == 0;
    }
    int n = _command(CMD_DIAGNOSE, &test, 1, d, sizeof(d));
    return n > 0 && d[0] == 0x00;
}

int PN532Full::send_command(uint8_t command, const uint8_t* data, size_t len, uint8_t* out, size_t outMax,
                            uint32_t timeoutMs) {
    return _command(command, data, len, out, outMax, timeoutMs);
}

bool PN532Full::start_command(uint8_t command, const uint8_t* data, size_t len) {
    return _start(command, data, len);
}

int PN532Full::read_response(uint8_t command, uint8_t* out, size_t outMax, uint32_t timeoutMs) {
    return _response(command, out, outMax, timeoutMs);
}

bool PN532Full::wait_ready(uint32_t timeoutMs) {
    return _wait(timeoutMs);
}

bool PN532Full::poll_interrupt() {
    return _ready();
}

void PN532Full::on_interrupt(void (*callback)(), InputPin* intPin) {
    InputPin* p = intPin ? intPin : _connection.intPin();
    if (!p) return;  // no IRQ line wired: the caller polls poll_interrupt()
    _callback = callback;
    _intPinUsed = p;
    _activeInstance = this;
    p->onEdge(&PN532Full::_edgeTrampoline, InputPin::kFalling);
}

void PN532Full::off_interrupt() {
    if (_intPinUsed) {
        _intPinUsed->offEdge(&PN532Full::_edgeTrampoline);
        _intPinUsed = nullptr;
    }
    _callback = nullptr;
    if (_activeInstance == this) _activeInstance = nullptr;
}

void PN532Full::_edgeTrampoline() {
    if (_activeInstance && _activeInstance->_callback) _activeInstance->_callback();
}
