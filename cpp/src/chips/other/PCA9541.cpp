#include "PCA9541.h"

// Out-of-class definitions for ODR-used static constexpr members; required
// before C++17 (AVR Arduino builds as C++11), redundant but valid after.
constexpr uint8_t PCA9541Minimal::I2C_ADDRESS;
constexpr uint8_t PCA9541Minimal::REG_IE;
constexpr uint8_t PCA9541Minimal::REG_CONTROL;
constexpr uint8_t PCA9541Minimal::REG_ISTAT;
constexpr uint8_t PCA9541Minimal::REG_AI;
constexpr uint8_t PCA9541Minimal::C_MYBUS;
constexpr uint8_t PCA9541Minimal::C_NMYBUS;
constexpr uint8_t PCA9541Minimal::C_BUSON;
constexpr uint8_t PCA9541Minimal::C_NBUSON;
constexpr uint8_t PCA9541Minimal::C_BUSINIT;
constexpr uint8_t PCA9541Minimal::C_TESTON;
constexpr uint8_t PCA9541Minimal::C_NTESTON;
constexpr uint8_t PCA9541Full::SOURCE_INT_IN;
constexpr uint8_t PCA9541Full::SOURCE_BUS_INIT;
constexpr uint8_t PCA9541Full::SOURCE_BUS_NOT_IDLE;
constexpr uint8_t PCA9541Full::SOURCE_BUS_LOST;
constexpr uint8_t PCA9541Full::SOURCE_TEST;
constexpr uint8_t PCA9541Full::SOURCE_OTHER_TEST;

PCA9541Full* PCA9541Full::_activeInstance = nullptr;

#ifdef __linux__
#include <unistd.h>
static void periph_delay_ms(unsigned ms) { usleep(ms * 1000); }
#elif defined(__ZEPHYR__)
#include <zephyr/kernel.h>
static void periph_delay_ms(unsigned ms) { k_sleep(K_MSEC(ms)); }
#elif defined(ESP_PLATFORM)
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
static void periph_delay_ms(unsigned ms) { vTaskDelay(pdMS_TO_TICKS(ms) ? pdMS_TO_TICKS(ms) : 1); }
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

// PCA9541Minimal

PCA9541Minimal::PCA9541Minimal(RegisterConnection& connection) : _connection(connection) {
    _readReg(REG_CONTROL);
    _present = _connection.ok();
    _readReg(REG_ISTAT);
}

void PCA9541Minimal::_delayMs(unsigned ms) { periph_delay_ms(ms); }

uint8_t PCA9541Minimal::_readReg(uint8_t reg) {
    uint8_t buf[1] = { 0 };
    _connection.read(reg, buf, 1);
    return buf[0];
}

void PCA9541Minimal::_writeReg(uint8_t reg, uint8_t value) {
    _connection.write(reg, &value, 1);
}

bool PCA9541Minimal::hasControl() {
    uint8_t c = _readReg(REG_CONTROL);
    return _hasControl(c) && _connected(c);
}

bool PCA9541Minimal::acquire(bool busInit, float timeoutMs) {
    uint8_t c = _readReg(REG_CONTROL);
    uint8_t nbuson = (c & C_NBUSON) ? 1 : 0;
    uint8_t nmybus = (c & C_NMYBUS) ? 1 : 0;
    uint8_t w = (uint8_t)(((1 - nbuson) << 2) | nmybus);
    if (busInit) w |= C_BUSINIT;
    _writeReg(REG_CONTROL, w);
    unsigned attempts = (unsigned)timeoutMs + 1;
    for (unsigned i = 0; i < attempts; i++) {
        if (busInit && !(_readReg(REG_ISTAT) & 0x02)) {
            _delayMs(1);
            continue;
        }
        if (hasControl()) return true;
        _delayMs(1);
    }
    return false;
}

void PCA9541Minimal::release() {
    uint8_t c = _readReg(REG_CONTROL);
    uint8_t nbuson = (c & C_NBUSON) ? 1 : 0;
    uint8_t nmybus = (c & C_NMYBUS) ? 1 : 0;
    _writeReg(REG_CONTROL, (uint8_t)(((1 - nbuson) << 2) | (1 - nmybus)));
}

// PCA9541Full

PCA9541Full::PCA9541Full(RegisterConnection& connection) : PCA9541Minimal(connection) {}

uint8_t PCA9541Full::getControl() { return _readReg(REG_CONTROL); }

bool PCA9541Full::isConnected() { return _connected(_readReg(REG_CONTROL)); }

PCA9541Full::Status PCA9541Full::status() {
    uint8_t c = _readReg(REG_CONTROL);
    Status s;
    s.hasControl = _hasControl(c);
    s.connected = _connected(c);
    s.busInitRequested = (c & C_BUSINIT) != 0;
    s.ownTest = (c & C_TESTON) != 0;
    s.otherTest = (c & C_NTESTON) != 0;
    return s;
}

void PCA9541Full::connect() {
    uint8_t c = _readReg(REG_CONTROL);
    uint8_t nbuson = (c & C_NBUSON) ? 1 : 0;
    _writeReg(REG_CONTROL, (uint8_t)(((1 - nbuson) << 2) | (c & C_MYBUS)));
}

void PCA9541Full::disconnect() {
    uint8_t c = _readReg(REG_CONTROL);
    uint8_t nbuson = (c & C_NBUSON) ? 1 : 0;
    _writeReg(REG_CONTROL, (uint8_t)((nbuson << 2) | (c & C_MYBUS)));
}

bool PCA9541Full::recoverBus(float timeoutMs) { return acquire(true, timeoutMs); }

void PCA9541Full::setInterruptMask(bool busLost, bool busNotIdle, bool busInit, bool intIn) {
    _writeReg(REG_IE, (uint8_t)((busLost ? 8 : 0) | (busNotIdle ? 4 : 0) | (busInit ? 2 : 0) | (intIn ? 1 : 0)));
}

uint8_t PCA9541Full::getInterruptMask() { return _readReg(REG_IE); }

void PCA9541Full::testInterrupt(bool own, bool other) {
    uint8_t c = _readReg(REG_CONTROL);
    uint8_t w = (uint8_t)((c & (C_BUSON | C_MYBUS)) | (own ? C_TESTON : 0) | (other ? C_NTESTON : 0));
    _writeReg(REG_CONTROL, w);
}

void PCA9541Full::readAll(uint8_t& ie, uint8_t& control, uint8_t& istat) {
    uint8_t buf[3] = { 0, 0, 0 };
    _connection.read(REG_AI | REG_IE, buf, 3);
    ie = buf[0];
    control = buf[1];
    istat = buf[2];
}

void PCA9541Full::enableInterrupt(uint8_t source) {
    uint8_t bit = source & 0x0F;
    if (bit) _writeReg(REG_IE, (uint8_t)(_readReg(REG_IE) & ~bit));
}

void PCA9541Full::disableInterrupt(uint8_t source) {
    uint8_t bit = source & 0x0F;
    if (bit) _writeReg(REG_IE, (uint8_t)(_readReg(REG_IE) | bit));
}

uint8_t PCA9541Full::pollInterrupt() { return _readReg(REG_ISTAT); }

void PCA9541Full::onInterrupt(void (*callback)(uint8_t), InputPin* intPin) {
    _callback = callback;
    InputPin* pin = intPin ? intPin : _connection.intPin();
    _intPinUsed = pin;
    if (!pin) return;
    _activeInstance = this;
    pin->onEdge(&PCA9541Full::_edgeTrampoline, InputPin::kFalling);
}

void PCA9541Full::offInterrupt() {
    if (_intPinUsed) {
        _intPinUsed->offEdge(&PCA9541Full::_edgeTrampoline);
        _intPinUsed = nullptr;
    }
    _callback = nullptr;
}

void PCA9541Full::_edgeTrampoline() {
    if (_activeInstance) _activeInstance->service();
}

void PCA9541Full::service() {
    uint8_t status = pollInterrupt();
    if (status && _callback) _callback(status);
}
