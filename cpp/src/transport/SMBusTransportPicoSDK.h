#pragma once
#include <string.h>
#include "I2CTransportPicoSDK.h"

/** @brief SMBus transport for the Raspberry Pi Pico SDK.
 *
 * Wraps `I2CTransportPicoSDK` and adds 7-bit address validation plus
 * optional software PEC (CRC-8, polynomial 0x07). Same approach as
 * `SMBusTransportZephyr`, just swapping in the `hardware_i2c` calls the
 * wrapped transport already makes.
 *
 * Bare-metal `pico-sdk` — no exceptions. PEC errors set an internal
 * error flag readable via `valid()` after each operation, the same
 * convention `SMBusTransport` (Arduino) already uses.
 *
 * Requires the `PICO_SDK_PATH` environment variable and `pico_sdk_init()`
 * in the consuming CMake project. Link against `hardware_i2c`.
 *
 * @param i2c I²C controller pointer (`i2c0` or `i2c1`).
 * @param addr 7-bit device address (0x08–0x77); sets `valid()` = false if out of range.
 * @param pec  Enable Packet Error Code (CRC-8) checking (default false).
 */
class SMBusTransportPicoSDK : public I2CTransportPicoSDK {
public:
    SMBusTransportPicoSDK(i2c_inst_t* i2c, uint8_t addr, bool pec = false)
        : I2CTransportPicoSDK(i2c, addr), _addr(addr), _pec(pec) {
        if (addr < 0x08 || addr > 0x77) _valid = false;
    }

    /** @brief Send bytes to the device, appending a PEC byte if enabled. */
    void write(const uint8_t* data, size_t len) override {
        _valid = true;
        if (_pec) {
            uint8_t buf[256];
            memcpy(buf, data, len);
            uint8_t addr_byte = _addr << 1;
            uint8_t crc = _crc8(&addr_byte, 1);
            crc = _crc8(data, len, crc);
            buf[len] = crc;
            I2CTransportPicoSDK::write(buf, len + 1);
        } else {
            I2CTransportPicoSDK::write(data, len);
        }
    }

    /** @brief Read bytes from the device, verifying the PEC byte if enabled.
     *
     *  Reads `len + 1` bytes when PEC is enabled; the trailing byte is
     *  the CRC. Call `valid()` after to check whether PEC matched.
     */
    void read(uint8_t* buf, size_t len) override {
        _valid = true;
        if (_pec) {
            uint8_t tmp[256];
            I2CTransportPicoSDK::read(tmp, len + 1);
            memcpy(buf, tmp, len);
            uint8_t addr_byte = (_addr << 1) | 1;
            uint8_t crc = _crc8(&addr_byte, 1);
            crc = _crc8(buf, len, crc);
            _valid = (crc == tmp[len]);
        } else {
            I2CTransportPicoSDK::read(buf, len);
        }
    }

    /** @brief Write then read with a repeated start, with PEC on the read phase.
     *
     *  PEC covers the full transaction (write address + data + read
     *  address + data). Call `valid()` after to check whether PEC matched.
     */
    void write_read(const uint8_t* data, size_t data_len,
                    uint8_t* buf, size_t buf_len) override {
        _valid = true;
        if (_pec) {
            uint8_t tmp[256];
            // Re-implement write_read so we can read buf_len + 1 bytes
            // (the +1 is the PEC byte) in one transaction.
            i2c_write_blocking(i2c_inst(), _addr, data, data_len, true);
            i2c_read_blocking(i2c_inst(), _addr, tmp, buf_len + 1, false);
            memcpy(buf, tmp, buf_len);
            uint8_t aw = _addr << 1;
            uint8_t ar = (_addr << 1) | 1;
            uint8_t crc = _crc8(&aw, 1);
            crc = _crc8(data, data_len, crc);
            crc = _crc8(&ar, 1, crc);
            crc = _crc8(buf, buf_len, crc);
            _valid = (crc == tmp[buf_len]);
        } else {
            I2CTransportPicoSDK::write_read(data, data_len, buf, buf_len);
        }
    }

    /** @brief Returns false if the address was out of range or the last
     *         read/write_read had a PEC mismatch. */
    bool valid() const { return _valid; }

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
