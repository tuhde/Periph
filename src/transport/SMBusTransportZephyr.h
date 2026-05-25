#pragma once
#include <string.h>
#include <zephyr/drivers/i2c.h>
#include "Transport.h"

/** @brief SMBus transport for Zephyr RTOS (wraps the i2c driver API with address validation and PEC).
 *
 * prj.conf must enable CONFIG_I2C=y, CONFIG_CPP=y, CONFIG_STD_CPP17=y.
 * The I²C device node must be enabled in the board's devicetree or an overlay.
 *
 * Enforces the valid 7-bit SMBus address range (0x08–0x77). When pec=true,
 * appends a CRC-8 byte to writes and verifies it on reads. Use valid() after
 * each read or write_read to check whether PEC matched.
 *
 * @param dev  I²C controller device pointer (e.g., DEVICE_DT_GET(DT_NODELABEL(i2c0))).
 * @param addr 7-bit device address (0x08–0x77); sets valid() = false if out of range.
 * @param pec  Enable Packet Error Code (CRC-8) checking (default false).
 */
class SMBusTransportZephyr : public Transport {
public:
    SMBusTransportZephyr(const struct device *dev, uint8_t addr, bool pec = false)
        : _dev(dev), _addr(addr), _pec(pec) {
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
            i2c_write(_dev, buf, len + 1, _addr);
        } else {
            i2c_write(_dev, data, len, _addr);
        }
    }

    /** @brief Read bytes from the device, verifying the PEC byte if enabled.
     *
     *  Reads len+1 bytes when PEC is enabled; the trailing byte is the CRC.
     *  Call valid() after to check whether PEC matched.
     */
    void read(uint8_t* buf, size_t len) override {
        _valid = true;
        if (_pec) {
            uint8_t tmp[256];
            i2c_read(_dev, tmp, len + 1, _addr);
            memcpy(buf, tmp, len);
            uint8_t addr_byte = (_addr << 1) | 1;
            uint8_t crc = _crc8(&addr_byte, 1);
            crc = _crc8(buf, len, crc);
            _valid = (crc == tmp[len]);
        } else {
            i2c_read(_dev, buf, len, _addr);
        }
    }

    /** @brief Write then read via i2c_write_read (repeated start), with PEC on the read phase.
     *
     *  PEC covers the full transaction (write address + data + read address + data).
     *  Call valid() after to check whether PEC matched.
     */
    void write_read(const uint8_t* data, size_t data_len,
                    uint8_t* buf, size_t buf_len) override {
        _valid = true;
        if (_pec) {
            uint8_t tmp[256];
            i2c_write_read(_dev, _addr, data, data_len, tmp, buf_len + 1);
            memcpy(buf, tmp, buf_len);
            uint8_t aw = _addr << 1;
            uint8_t ar = (_addr << 1) | 1;
            uint8_t crc = _crc8(&aw, 1);
            crc = _crc8(data, data_len, crc);
            crc = _crc8(&ar, 1, crc);
            crc = _crc8(buf, buf_len, crc);
            _valid = (crc == tmp[buf_len]);
        } else {
            i2c_write_read(_dev, _addr, data, data_len, buf, buf_len);
        }
    }

    /** @brief Returns false if the address was out of range or the last read/write_read had a PEC mismatch. */
    bool valid() const { return _valid; }

private:
    const struct device *_dev;
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
