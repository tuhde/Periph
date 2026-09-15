#pragma once
#include <stdint.h>
#include <stddef.h>
#include <map>
#include <vector>
#include <deque>
#include "Connection.h"

/** @brief In-memory fake SPI connection for unit tests — no hardware, no bus.
 *
 * Models full-duplex SPI: every transaction sends and receives
 * simultaneously. Two preloading patterns are supported:
 *
 * - Plain reads via `queueRead(data)`: each `_read(buf, n)` call pops
 *   the next queued response.
 * - Command-then-read via `setRegister(reg, values)`: when the chip
 *   calls `write_read([cmd_byte], n)`, the mock treats `cmd_byte` as a
 *   register address and returns `n` consecutive register bytes
 *   starting at that address.
 *
 * Every `write()` call is recorded on `writes()` for assertions, and
 * 2+ byte writes also update the register map at the command-byte
 * address so a later write_read sees them.
 */
class SPIConnectionMock : public Connection {
public:
    SPIConnectionMock() = default;

    /** @brief Preload consecutive register bytes starting at @p reg. */
    void setRegister(uint8_t reg, std::initializer_list<uint8_t> values) {
        uint8_t offset = 0;
        for (uint8_t v : values) {
            _registers[reg + offset] = v;
            offset++;
        }
    }

    /** @brief Queue bytes returned by the next plain `read()` call. */
    void queueRead(std::initializer_list<uint8_t> data) {
        _readQueue.emplace_back(data.begin(), data.end());
    }

    /** @brief Log of every write/write_read command phase, in call order. */
    const std::vector<std::vector<uint8_t>>& writes() const { return _writes; }

    /** @brief Direct access to the register map for assertions. */
    const std::map<uint8_t, uint8_t>& registers() const { return _registers; }

protected:
    void _write(const uint8_t* data, size_t len) override {
        _writes.emplace_back(data, data + len);
        if (len >= 2) {
            uint8_t reg = data[0];
            for (size_t i = 1; i < len; i++) {
                _registers[reg + i - 1] = data[i];
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
        if (data_len == 0) {
            for (size_t i = 0; i < buf_len; i++) buf[i] = 0;
            return;
        }
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
