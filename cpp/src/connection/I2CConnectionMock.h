#pragma once
#include <stdint.h>
#include <stddef.h>
#include <map>
#include <vector>
#include <deque>
#include "Connection.h"

/** @brief In-memory fake I2C connection for unit tests — no hardware, no bus.
 *
 *  Supports the two access patterns chip drivers in this repo use:
 *
 *  - Register-addressed reads (`write_read(&reg, 1, buf, n)`): backed by a
 *    byte-addressable register map. Preload it with setRegister() before
 *    constructing the chip.
 *  - Plain streamed reads (`read(buf, n)`, no register address): backed by
 *    a FIFO queue. Preload responses with queueRead(); each read() call pops
 *    the next one. Falls back to zero bytes if the queue is empty.
 *
 *  Every write() call (register writes and plain command writes alike) is
 *  appended to writes() for assertions, and 2+ byte writes are also applied
 *  to the register map so a later write_read() sees them.
 *
 *  The register address is assumed to be a single byte (the first byte sent)
 *  unless setAddressWidth() is called with a wider value — needed by chips
 *  such as the ADE7953 that address registers with 2 bytes.
 */
class I2CConnectionMock : public Connection {
public:
    I2CConnectionMock() = default;

    /** @brief Set the register address width in bytes (default 1). */
    void setAddressWidth(uint8_t addressWidth) { _addressWidth = addressWidth; }

    /** @brief Preload consecutive register bytes starting at @p reg. */
    void setRegister(uint16_t reg, std::initializer_list<uint8_t> values) {
        uint16_t offset = 0;
        for (uint8_t v : values) {
            _registers[reg + offset] = v;
            offset++;
        }
    }

    /** @brief Queue bytes to be returned by the next plain read() call. */
    void queueRead(std::initializer_list<uint8_t> data) {
        _readQueue.emplace_back(data.begin(), data.end());
    }

    /** @brief Log of every write()/write_read() write phase, in call order. */
    const std::vector<std::vector<uint8_t>>& writes() const { return _writes; }

    /** @brief Direct access to the register map for assertions. */
    const std::map<uint16_t, uint8_t>& registers() const { return _registers; }

protected:
    void _write(const uint8_t* data, size_t len) override {
        _writes.emplace_back(data, data + len);
        if (len > _addressWidth) {
            uint16_t reg = readAddress(data);
            for (size_t i = _addressWidth; i < len; i++) {
                _registers[static_cast<uint16_t>(reg + i - _addressWidth)] = data[i];
            }
        }
    }

    void _read(uint8_t* buf, size_t len) override {
        if (!_readQueue.empty()) {
            const auto& front = _readQueue.front();
            for (size_t i = 0; i < len; i++) {
                buf[i] = i < front.size() ? front[i] : 0;
            }
            _readQueue.pop_front();
            return;
        }
        for (size_t i = 0; i < len; i++) buf[i] = 0;
    }

    void _write_read(const uint8_t* data, size_t data_len,
                      uint8_t* buf, size_t buf_len) override {
        _writes.emplace_back(data, data + data_len);
        uint16_t reg = readAddress(data);
        for (size_t i = 0; i < buf_len; i++) {
            auto it = _registers.find(static_cast<uint16_t>(reg + i));
            buf[i] = it != _registers.end() ? it->second : 0;
        }
    }

private:
    uint16_t readAddress(const uint8_t* data) const {
        uint16_t reg = 0;
        for (uint8_t i = 0; i < _addressWidth; i++) {
            reg = static_cast<uint16_t>((reg << 8) | data[i]);
        }
        return reg;
    }

    std::map<uint16_t, uint8_t>         _registers;
    std::vector<std::vector<uint8_t>>   _writes;
    std::deque<std::vector<uint8_t>>    _readQueue;
    uint8_t                             _addressWidth = 1;
};
