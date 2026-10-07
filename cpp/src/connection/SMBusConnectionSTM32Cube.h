#pragma once
#include <string.h>
#include <stm32f4xx_hal.h>
#include "I2CConnectionSTM32Cube.h"

/** @brief SMBus connection for STM32Cube.
 *
 * Wraps `I2CConnectionSTM32Cube` and adds the same 7-bit address
 * validation and software PEC (CRC-8, polynomial 0x07) as
 * `SMBusConnectionZephyr`/`SMBusConnectionESPIDF`/`SMBusConnectionPicoSDK`.
 *
 * STM32Cube C++ code in this repo does not rely on exceptions, so PEC
 * errors set an internal error flag readable via `valid()` after each
 * operation — same convention as every other platform's SMBus connection.
 *
 * @param hi2c   I²C handle, already initialised via `HAL_I2C_Init()`.
 * @param addr   7-bit device address (0x08–0x77); sets `valid()` = false
 *               if out of range.
 * @param pec    Enable Packet Error Code (CRC-8) checking (default false).
 * @param intPin Optional InputPin for INT-line delivery.
 * @param enPin  Optional OutputPin for hardware enable/power control.
 * @param regBytes  Register address width in bytes, big-endian (default 1).
 * @param enActiveHigh True if the EN pin is active-high (default); false for active-low.
 */
class SMBusConnectionSTM32Cube : public I2CConnectionSTM32Cube {
public:
    SMBusConnectionSTM32Cube(I2C_HandleTypeDef* hi2c, uint8_t addr, bool pec = false,
                             InputPin* intPin = nullptr, OutputPin* enPin = nullptr,
                             uint8_t regBytes = 1, bool enActiveHigh = true)
        : I2CConnectionSTM32Cube(hi2c, addr, intPin, enPin, regBytes, enActiveHigh), _addr(addr), _pec(pec) {
        if (addr < 0x08 || addr > 0x77) _valid = false;
    }

    /** @brief Returns false if the address was out of range or the last
     *         read/write_read had a PEC mismatch. */
    bool valid() const { return _valid; }

protected:
    /** @brief Send bytes to the device, appending a PEC byte if enabled. */
    void _write(const uint8_t* data, size_t len) override {
        _valid = true;
        if (_pec) {
            uint8_t buf[256];
            memcpy(buf, data, len);
            uint8_t addr_byte = _addr << 1;
            uint8_t crc = _crc8(&addr_byte, 1);
            crc = _crc8(data, len, crc);
            buf[len] = crc;
            I2CConnectionSTM32Cube::_write(buf, len + 1);
        } else {
            I2CConnectionSTM32Cube::_write(data, len);
        }
    }

    /** @brief Read bytes from the device, verifying the PEC byte if enabled.
     *
     *  Reads `len + 1` bytes when PEC is enabled; the trailing byte is
     *  the CRC. Call `valid()` after to check whether PEC matched.
     */
    void _read(uint8_t* buf, size_t len) override {
        _valid = true;
        if (_pec) {
            uint8_t tmp[256];
            I2CConnectionSTM32Cube::_read(tmp, len + 1);
            memcpy(buf, tmp, len);
            uint8_t addr_byte = (_addr << 1) | 1;
            uint8_t crc = _crc8(&addr_byte, 1);
            crc = _crc8(buf, len, crc);
            _valid = (crc == tmp[len]);
        } else {
            I2CConnectionSTM32Cube::_read(buf, len);
        }
    }

    /** @brief Write then read with PEC on the read phase.
     *
     *  PEC covers the full transaction (write address + data + read
     *  address + data). Call `valid()` after to check whether PEC
     *  matched.
     */
    void _write_read(const uint8_t* data, size_t data_len,
                     uint8_t* buf, size_t buf_len) override {
        _valid = true;
        if (_pec) {
            uint8_t tmp[256];
            I2CConnectionSTM32Cube::_write(data, data_len);
            I2CConnectionSTM32Cube::_read(tmp, buf_len + 1);
            memcpy(buf, tmp, buf_len);
            uint8_t aw = _addr << 1;
            uint8_t ar = (_addr << 1) | 1;
            uint8_t crc = _crc8(&aw, 1);
            crc = _crc8(data, data_len, crc);
            crc = _crc8(&ar, 1, crc);
            crc = _crc8(buf, buf_len, crc);
            _valid = (crc == tmp[buf_len]);
        } else {
            I2CConnectionSTM32Cube::_write_read(data, data_len, buf, buf_len);
        }
    }

private:
    uint8_t _addr;
    bool    _pec;
    bool    _valid = true;

    static uint8_t _crc8(const uint8_t* data, size_t len, uint8_t crc = 0) {
        for (size_t i = 0; i < len; i++) {
            crc ^= data[i];
            for (uint8_t b = 0; b < 8; b++)
                crc = (crc & 0x80) ? (crc << 1) ^ 0x07 : crc << 1;
        }
        return crc;
    }
};
