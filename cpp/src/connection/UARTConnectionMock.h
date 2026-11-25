#pragma once
#include <stdint.h>
#include <stddef.h>
#include <string.h>
#include <deque>
#include <vector>
#include <map>
#include "Connection.h"

/** @brief In-memory TMC2208-style UART chip simulator for unit tests — no hardware.
 *
 *  Behaves like a Trinamic single-wire UART slave: write() accepts 8-byte
 *  write datagrams (stores the register, bumps IFCNT) and 4-byte read
 *  requests (queues the reply from regs). With echo enabled every transmitted
 *  byte is also queued back, as on the shared PDN_UART wire. All writes are
 *  kept in datagrams()/writes() for assertions.
 */
class UARTConnectionMock : public Connection {
public:
    /** @brief One register write: address and 32-bit value. */
    struct RegWrite { uint8_t reg; uint32_t value; };

    explicit UARTConnectionMock(bool echo = true) : _echo(echo) {
        regs[0x06] = 0x21000100UL;
        regs[0x02] = 0;
    }

    /** @brief CRC-8 (polynomial 0x07, init 0, LSB first) over @p len bytes. */
    static uint8_t crc8(const uint8_t* data, size_t len) {
        uint8_t crc = 0;
        for (size_t i = 0; i < len; i++) {
            uint8_t byte = data[i];
            for (int b = 0; b < 8; b++) {
                if ((crc >> 7) ^ (byte & 1)) crc = (uint8_t)((crc << 1) ^ 0x07);
                else crc = (uint8_t)(crc << 1);
                byte >>= 1;
            }
        }
        return crc;
    }

    /** @brief Register file returned on reads and updated by write datagrams. */
    std::map<uint8_t, uint32_t> regs;
    /** @brief When true the next read reply carries a corrupted CRC (cleared after use). */
    bool corruptNextReply = false;

    /** @brief Every datagram passed to write(), in order. */
    const std::vector<std::vector<uint8_t>>& datagrams() const { return _datagrams; }
    /** @brief Every register write, in order. */
    const std::vector<RegWrite>& writes() const { return _writes; }

    /** @brief Last value written to @p reg, or -1 if never written. */
    int64_t lastWrite(uint8_t reg) const {
        int64_t v = -1;
        for (const auto& w : _writes) if (w.reg == reg) v = w.value;
        return v;
    }

    size_t available() const override { return _rx.size(); }

protected:
    void _write(const uint8_t* data, size_t len) override {
        _datagrams.emplace_back(data, data + len);
        if (_echo) _rx.insert(_rx.end(), data, data + len);
        if (len == 8 && (data[2] & 0x80)) {
            uint8_t reg = data[2] & 0x7F;
            uint32_t value = ((uint32_t)data[3] << 24) | ((uint32_t)data[4] << 16) |
                             ((uint32_t)data[5] << 8) | data[6];
            regs[reg] = value;
            _writes.push_back({reg, value});
            regs[0x02] = (regs[0x02] + 1) & 0xFF;
        } else if (len == 4) {
            uint8_t reg = data[2] & 0x7F;
            uint32_t v = regs.count(reg) ? regs[reg] : 0;
            uint8_t body[8] = {0x05, 0xFF, reg, (uint8_t)(v >> 24), (uint8_t)(v >> 16),
                               (uint8_t)(v >> 8), (uint8_t)v, 0};
            body[7] = crc8(body, 7);
            if (corruptNextReply) { body[7] ^= 0xFF; corruptNextReply = false; }
            _rx.insert(_rx.end(), body, body + 8);
        }
    }

    void _read(uint8_t* buf, size_t len) override {
        for (size_t i = 0; i < len; i++) {
            if (_rx.empty()) { buf[i] = 0; continue; }
            buf[i] = _rx.front();
            _rx.pop_front();
        }
    }

    void _write_read(const uint8_t* data, size_t data_len, uint8_t* buf, size_t buf_len) override {
        _write(data, data_len);
        _read(buf, buf_len);
    }

private:
    bool _echo;
    std::deque<uint8_t> _rx;
    std::vector<std::vector<uint8_t>> _datagrams;
    std::vector<RegWrite> _writes;
};
