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
 */
class I2CConnectionMock : public Connection {
public:
    I2CConnectionMock() = default;

    /** @brief Preload consecutive register bytes starting at @p reg. */
    void setRegister(uint8_t reg, std::initializer_list<uint8_t> values) {
        uint8_t offset = 0;
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
    const std::map<uint8_t, uint8_t>& registers() const { return _registers; }

protected:
    void _write(const uint8_t* data, size_t len) override {
        _writes.emplace_back(data, data + len);
        if (len >= 2) {
            uint8_t reg = data[0];
            for (size_t i = 1; i < len; i++) {
                _registers[static_cast<uint8_t>(reg + i - 1)] = data[i];
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
        uint8_t reg = data[0];
        for (size_t i = 0; i < buf_len; i++) {
            auto it = _registers.find(static_cast<uint8_t>(reg + i));
            buf[i] = it != _registers.end() ? it->second : 0;
        }
    }

private:
    std::map<uint8_t, uint8_t>          _registers;
    std::vector<std::vector<uint8_t>>   _writes;
    std::deque<std::vector<uint8_t>>    _readQueue;
};
