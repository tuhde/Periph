#pragma once
#include <string.h>
#include <driver/i2c_master.h>
#include "I2CConnectionESPIDF.h"

/** @brief SMBus connection for ESP-IDF.
 *
 * Wraps `I2CConnectionESPIDF` and adds the same 7-bit address
 * validation and software PEC (CRC-8, polynomial 0x07) as
 * `SMBusConnectionZephyr`, swapping in the driver-ng
 * `i2c_master_transmit` / `i2c_master_receive` /
 * `i2c_master_transmit_receive` calls the wrapped connection already
 * makes.
 *
 * ESP-IDF C++ code in this repo does not rely on exceptions, so PEC
 * errors set an internal error flag readable via `valid()` after each
 * operation — same convention as `SMBusConnection` (Arduino) and
 * `SMBusConnectionPicoSDK`.
 *
 * Requires ESP-IDF ≥5.1 exported (`IDF_PATH` set, `idf.py` on PATH).
 * The consuming project must link `driver` and provide a configured
 * `i2c_master_dev_handle_t` to construct the underlying I²C connection.
 *
 * @param dev    I²C device handle, already added to a bus via
 *               `i2c_master_bus_add_device()`.
 * @param addr   7-bit device address (0x08–0x77); sets `valid()` = false
 *               if out of range.
 * @param pec    Enable Packet Error Code (CRC-8) checking (default false).
 * @param intPin Optional InputPin for INT-line delivery.
 * @param enPin  Optional OutputPin for hardware enable/power control.
 */
class SMBusConnectionESPIDF : public I2CConnectionESPIDF {
public:
    SMBusConnectionESPIDF(i2c_master_dev_handle_t dev, uint8_t addr, bool pec = false,
                          InputPin* intPin = nullptr, OutputPin* enPin = nullptr)
        : I2CConnectionESPIDF(dev, intPin, enPin), _addr(addr), _pec(pec) {
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
            I2CConnectionESPIDF::_write(buf, len + 1);
        } else {
            I2CConnectionESPIDF::_write(data, len);
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
            I2CConnectionESPIDF::_read(tmp, len + 1);
            memcpy(buf, tmp, len);
            uint8_t addr_byte = (_addr << 1) | 1;
            uint8_t crc = _crc8(&addr_byte, 1);
            crc = _crc8(buf, len, crc);
            _valid = (crc == tmp[len]);
        } else {
            I2CConnectionESPIDF::_read(buf, len);
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
            // Re-implement write_read so we can read buf_len + 1 bytes
            // (the +1 is the PEC byte) in one transaction.
            i2c_master_transmit_receive(i2c_dev(), data, data_len, tmp,
                                        buf_len + 1, -1);
            memcpy(buf, tmp, buf_len);
            uint8_t aw = _addr << 1;
            uint8_t ar = (_addr << 1) | 1;
            uint8_t crc = _crc8(&aw, 1);
            crc = _crc8(data, data_len, crc);
            crc = _crc8(&ar, 1, crc);
            crc = _crc8(buf, buf_len, crc);
            _valid = (crc == tmp[buf_len]);
        } else {
            I2CConnectionESPIDF::_write_read(data, data_len, buf, buf_len);
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
