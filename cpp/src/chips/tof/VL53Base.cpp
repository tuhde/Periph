#include "VL53Base.h"

#ifdef __linux__
#include <time.h>
#include <unistd.h>
static void _delay_ms(unsigned ms) { usleep(ms * 1000); }
static uint32_t _millis() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint32_t)(ts.tv_sec * 1000 + ts.tv_nsec / 1000000);
}
#elif defined(__ZEPHYR__)
#include <zephyr/kernel.h>
static void _delay_ms(unsigned ms) { k_sleep(K_MSEC(ms)); }
static uint32_t _millis() { return (uint32_t)k_uptime_get(); }
#elif defined(ESP_PLATFORM)
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
static void _delay_ms(unsigned ms) { vTaskDelay(pdMS_TO_TICKS(ms) ? pdMS_TO_TICKS(ms) : 1); }
static uint32_t _millis() { return (uint32_t)(xTaskGetTickCount() * portTICK_PERIOD_MS); }
#elif __has_include(<pico/time.h>)
#include <pico/time.h>
static void _delay_ms(unsigned ms) { sleep_ms(ms); }
static uint32_t _millis() { return to_ms_since_boot(get_absolute_time()); }
#else
#include <Arduino.h>
static void _delay_ms(unsigned ms) { delay(ms); }
static uint32_t _millis() { return millis(); }
#endif

VL53Base* VL53Base::_subscribers[VL53Base::MAX_SUBSCRIBERS] = {};

void (*const VL53Base::_trampolines[VL53Base::MAX_SUBSCRIBERS])() = {
    &VL53Base::_trampoline<0>,
    &VL53Base::_trampoline<1>,
    &VL53Base::_trampoline<2>,
    &VL53Base::_trampoline<3>,
};

VL53Base::~VL53Base() {
    if (_slot >= 0) _unsubscribe();
}

uint32_t VL53Base::_millisNow() { return _millis(); }

void VL53Base::_wrBlock(uint16_t reg, const uint8_t* data, size_t len) {
    // Largest write in the family: VL53L0X's 6-byte reference-SPAD map, 4-byte values.
    uint8_t buf[2 + 16];
    size_t n = 0;
    if (_indexBytes == 2) buf[n++] = (uint8_t)(reg >> 8);
    buf[n++] = (uint8_t)(reg & 0xFF);
    for (size_t i = 0; i < len && i < 16; i++) buf[n++] = data[i];
    _connection.write(buf, n);
}

void VL53Base::_rdBlock(uint16_t reg, uint8_t* buf, size_t len) {
    uint8_t index[2];
    size_t n = 0;
    if (_indexBytes == 2) index[n++] = (uint8_t)(reg >> 8);
    index[n++] = (uint8_t)(reg & 0xFF);
    _connection.write_read(index, n, buf, len);
}

void VL53Base::_wr8(uint16_t reg, uint8_t value) { _wrBlock(reg, &value, 1); }

uint8_t VL53Base::_rd8(uint16_t reg) {
    uint8_t value = 0;
    _rdBlock(reg, &value, 1);
    return value;
}

void VL53Base::_wr16(uint16_t reg, uint16_t value) {
    uint8_t buf[2] = { (uint8_t)(value >> 8), (uint8_t)(value & 0xFF) };
    _wrBlock(reg, buf, 2);
}

uint16_t VL53Base::_rd16(uint16_t reg) {
    uint8_t buf[2] = { 0, 0 };
    _rdBlock(reg, buf, 2);
    return (uint16_t)((buf[0] << 8) | buf[1]);
}

void VL53Base::_wr32(uint16_t reg, uint32_t value) {
    uint8_t buf[4] = { (uint8_t)(value >> 24), (uint8_t)(value >> 16), (uint8_t)(value >> 8),
                       (uint8_t)(value & 0xFF) };
    _wrBlock(reg, buf, 4);
}

uint32_t VL53Base::_rd32(uint16_t reg) {
    uint8_t buf[4] = { 0, 0, 0, 0 };
    _rdBlock(reg, buf, 4);
    return ((uint32_t)buf[0] << 24) | ((uint32_t)buf[1] << 16) | ((uint32_t)buf[2] << 8) | buf[3];
}

void VL53Base::_bootWait() {
    if (_connection.enPin()) _connection.enable();
    _delay_ms(BOOT_MS);
}

bool VL53Base::_setAddressReg(uint16_t reg, uint8_t address) {
    if (address < 0x08 || address > 0x77) return false;
    _wr8(reg, address & 0x7F);
    return true;
}

void VL53Base::_subscribe(void (*callback)(uint8_t status), InputPin* intPin) {
    _callback = callback;
    InputPin* pin = intPin ? intPin : _connection.intPin();
    _intPinUsed = pin;
    if (!pin) return;
    if (_slot < 0) {
        for (int i = 0; i < MAX_SUBSCRIBERS; i++) {
            if (!_subscribers[i]) {
                _slot = i;
                break;
            }
        }
        if (_slot < 0) {
            // More simultaneous subscribers than trampolines: no delivery;
            // the caller can still use pollInterrupt().
            _intPinUsed = nullptr;
            return;
        }
    }
    _subscribers[_slot] = this;
    pin->onEdge(_trampolines[_slot], InputPin::kFalling);
}

void VL53Base::_unsubscribe() {
    if (_intPinUsed && _slot >= 0) _intPinUsed->offEdge(_trampolines[_slot]);
    if (_slot >= 0) _subscribers[_slot] = nullptr;
    _slot = -1;
    _intPinUsed = nullptr;
    _callback = nullptr;
}

void VL53Base::_handleEdge() {
    uint8_t status = _pollInterruptStatus();
    if (status && _callback) _callback(status);
}
