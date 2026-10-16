#pragma once
#include <stdint.h>
#include <stddef.h>
#include <vector>
#include "DisplayConnectionBase.h"

/** @brief In-memory fake display connection for unit tests — no hardware, no bus.
 *
 * Has the public interface of the real connections (sendCommand, sendData,
 * readData, reset, waitBusy, busyLevel, enable/disable) and records every call
 * in order in events(), so a chip-driver test can assert the exact command
 * stream and the reset/BUSY handling. Host-only (uses std::vector), like the
 * other connection mocks.
 *
 * @param wireMode kSimplex makes readData() return false, as the real
 *        connection does.
 */
class DisplayConnectionMock {
public:
    struct Event {
        enum Kind { kReset, kCommand, kData, kRead, kWaitBusy, kDelay } kind;
        uint8_t               cmd = 0;               // kCommand
        std::vector<uint8_t>  bytes;                 // kCommand parameters / kData payload
        size_t                length = 0;            // kRead
        bool                  busyActiveHigh = false; // kWaitBusy
        uint32_t              timeoutMs = 0;         // kWaitBusy, kDelay (milliseconds)
    };

    explicit DisplayConnectionMock(DisplayWireMode wireMode = DisplayWireMode::kSimplex)
        : _wireMode(wireMode) {}

    bool valid() const { return true; }
    DisplayWireMode wireMode() const { return _wireMode; }

    void enable()  { _enabled = true; }
    void disable() { _enabled = false; }
    bool isEnabled() const { return _enabled; }

    void reset() {
        if (!_enabled) return;
        Event e; e.kind = Event::kReset;
        _events.push_back(e);
    }

    void delay(uint32_t ms) {
        Event e; e.kind = Event::kDelay; e.timeoutMs = ms;
        _events.push_back(e);
    }

    bool busyLevel() const { return _busyHigh; }

    bool waitBusy(bool busyActiveHigh, uint32_t timeoutMs = DisplayConnectionBase::kWaitForever) {
        Event e; e.kind = Event::kWaitBusy;
        e.busyActiveHigh = busyActiveHigh;
        e.timeoutMs = timeoutMs;
        _events.push_back(e);
        return _ready;
    }

    void sendCommand(uint8_t cmd, const uint8_t* data = nullptr, size_t len = 0) {
        if (!_enabled) return;
        Event e; e.kind = Event::kCommand; e.cmd = cmd;
        if (data != nullptr) e.bytes.assign(data, data + len);
        _events.push_back(e);
    }

    void sendData(const uint8_t* data, size_t len) {
        if (!_enabled) return;
        Event e; e.kind = Event::kData;
        e.bytes.assign(data, data + len);
        _events.push_back(e);
    }

    /** @brief Return queued bytes (zero-padded when the queue runs dry); false in kSimplex. */
    bool readData(uint8_t* buf, size_t len) {
        if (_wireMode == DisplayWireMode::kSimplex) return false;
        if (!_enabled) {
            for (size_t i = 0; i < len; i++) buf[i] = 0;
            return true;
        }
        Event e; e.kind = Event::kRead; e.length = len;
        _events.push_back(e);
        for (size_t i = 0; i < len; i++) {
            if (_readPos < _readQueue.size()) buf[i] = _readQueue[_readPos++];
            else buf[i] = 0;
        }
        return true;
    }

    // ---- test controls -------------------------------------------------------------
    /** @brief Queue bytes that later readData() calls return, in order. */
    void queueRead(const uint8_t* data, size_t len) {
        _readQueue.insert(_readQueue.end(), data, data + len);
    }
    /** @brief Result returned by waitBusy(); false simulates a timeout. */
    void setReady(bool ready) { _ready = ready; }
    /** @brief Raw level returned by busyLevel(). */
    void setBusyLevel(bool high) { _busyHigh = high; }

    const std::vector<Event>& events() const { return _events; }

    /** @brief Number of reset() calls recorded. */
    size_t resetCount() const {
        size_t n = 0;
        for (size_t i = 0; i < _events.size(); i++) if (_events[i].kind == Event::kReset) n++;
        return n;
    }
    /** @brief Number of sendCommand() calls recorded. */
    size_t commandCount() const {
        size_t n = 0;
        for (size_t i = 0; i < _events.size(); i++) if (_events[i].kind == Event::kCommand) n++;
        return n;
    }

private:
    DisplayWireMode      _wireMode;
    bool                 _enabled = true;
    bool                 _ready = true;
    bool                 _busyHigh = false;
    std::vector<Event>   _events;
    std::vector<uint8_t> _readQueue;
    size_t               _readPos = 0;
};
