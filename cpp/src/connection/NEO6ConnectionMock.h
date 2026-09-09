#pragma once
#include <stdint.h>
#include <stddef.h>
#include <deque>
#include <vector>
#include <initializer_list>
#include "Connection.h"

/** @brief In-memory fake connection for NEO-6 unit tests — no hardware, no bus.
 *
 *  NEO-6's driver treats UART, I2C (DDC), and SPI as the same underlying
 *  NMEA/UBX byte stream (see specs/gnss/neo-6.md's "Connection abstraction"
 *  note): read(1) for UART, write_read(0xFF-prefix, 1) for I2C,
 *  write_read(empty-prefix, 1) for SPI. This mock is transport-shape
 *  agnostic: preload the stream with queueBytes(); _read() and
 *  _write_read() both just pop the next byte(s) off the front of one shared
 *  FIFO, regardless of the prefix (if any) passed to write_read — matching
 *  the real module, where all three transports deliver the same bytes and
 *  only the framing differs. write() calls (e.g. sendUbx) are logged to
 *  writes() for assertions.
 *
 *  available() reports the real queue depth so NEO6Minimal's UART path
 *  (which gates read() behind available() == 0) stops cleanly once the
 *  queue is drained instead of reading fabricated zero bytes; the I2C/SPI
 *  paths never check available() (a real DDC/SPI transfer always yields a
 *  byte, real or idle filler), so an empty queue there just yields 0x00,
 *  which can never masquerade as a sentence start ('$' = 0x24).
 */
class NEO6ConnectionMock : public Connection {
public:
    NEO6ConnectionMock() = default;

    /** @brief Append bytes to the end of the shared read stream. */
    void queueBytes(const uint8_t* data, size_t len) {
        _stream.insert(_stream.end(), data, data + len);
    }

    /** @brief Append bytes to the end of the shared read stream. */
    void queueBytes(std::initializer_list<uint8_t> data) {
        _stream.insert(_stream.end(), data.begin(), data.end());
    }

    /** @brief Append bytes to the end of the shared read stream. */
    void queueBytes(const std::vector<uint8_t>& data) {
        _stream.insert(_stream.end(), data.begin(), data.end());
    }

    /** @brief Log of every write() write phase, in call order. */
    const std::vector<std::vector<uint8_t>>& writes() const { return _writes; }

    /** @brief Bytes still queued and not yet popped by read()/write_read(). */
    size_t available() const override { return _stream.size(); }

protected:
    void _write(const uint8_t* data, size_t len) override {
        _writes.emplace_back(data, data + len);
    }

    void _read(uint8_t* buf, size_t len) override {
        _popInto(buf, len);
    }

    void _write_read(const uint8_t* /*data*/, size_t /*data_len*/,
                      uint8_t* buf, size_t buf_len) override {
        _popInto(buf, buf_len);
    }

private:
    void _popInto(uint8_t* buf, size_t len) {
        for (size_t i = 0; i < len; i++) {
            if (!_stream.empty()) {
                buf[i] = _stream.front();
                _stream.pop_front();
            } else {
                buf[i] = 0;
            }
        }
    }

    std::deque<uint8_t>               _stream;
    std::vector<std::vector<uint8_t>> _writes;
};
