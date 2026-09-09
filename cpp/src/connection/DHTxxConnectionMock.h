#pragma once
#include <stdint.h>
#include <deque>
#include <cstring>

/** @brief In-memory fake DHTxx connection for unit tests — no hardware, no GPIO.
 *
 *  DHTxx has no register map and no framing to fake at this level: the chip
 *  driver only ever calls read(out), which fills a 5-byte buffer and returns
 *  a bool (matching DHTxxConnectionLinux::read's timeout/framing-error
 *  contract). Preload frames with queueRead(); preload a forced failure
 *  (connection-level timeout/framing error, distinct from a checksum error,
 *  which is the chip driver's concern) with queueFailure(). Each read() call
 *  pops the next queued item, falling back to returning 5 zero bytes and
 *  `true` if the queue is empty.
 *
 *  This is a plain duck-typed mock, not a Connection subclass — DHT11Minimal
 *  / DHT11Full are templates over Connection with no virtual interface, so
 *  any type with matching method signatures plugs in directly.
 */
class DHTxxConnectionMock {
public:
    DHTxxConnectionMock() = default;

    /** @brief Queue a 5-byte frame to be returned (with a `true` result) by the next read(). */
    void queueRead(const uint8_t frame[5]) {
        Item item;
        item.fail = false;
        std::memcpy(item.frame, frame, 5);
        _queue.push_back(item);
    }

    /** @brief Queue a connection-level failure (timeout/framing error) for the next read(). */
    void queueFailure() {
        Item item;
        item.fail = true;
        std::memset(item.frame, 0, 5);
        _queue.push_back(item);
    }

    void enable() { _enabled = true; }
    void disable() { _enabled = false; }
    bool isEnabled() const { return _enabled; }
    void close() {}

    bool read(uint8_t* out) {
        if (!_enabled) {
            std::memset(out, 0, 5);
            return false;
        }
        if (_queue.empty()) {
            std::memset(out, 0, 5);
            return true;
        }
        Item item = _queue.front();
        _queue.pop_front();
        std::memcpy(out, item.frame, 5);
        return !item.fail;
    }

private:
    struct Item {
        bool fail;
        uint8_t frame[5];
    };
    std::deque<Item> _queue;
    bool _enabled = true;
};
