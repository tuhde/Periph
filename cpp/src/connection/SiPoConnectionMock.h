#pragma once
#include <stdint.h>
#include <stddef.h>
#include <stdexcept>
#include <vector>

/** @brief In-memory fake SiPo connection for unit tests — no hardware, no bus.
 *
 * Models the write-only shift-register protocol chip drivers in this repo
 * use (TPIC6B595, SN74HC595, ...): `write()` shifts + latches, `clear()`
 * pulses SRCLR, `set_output_enable()` drives G. SRCLR/G availability is
 * configurable at construction; when unavailable, `clear()`/
 * `set_output_enable()` throw `std::runtime_error`, matching
 * SiPoConnectionLinux's documented (`@throws`) behavior — the platform
 * chip drivers must tolerate, since it differs from the other four C++
 * SiPo connections' bool/int-return convention.
 */
class SiPoConnectionMock {
public:
    explicit SiPoConnectionMock(bool hasSrclr = true, bool hasG = true)
        : _hasSrclr(hasSrclr), _hasG(hasG) {}

    void write(const uint8_t* data, size_t len) {
        if (!_enabled) return;
        _writes.emplace_back(data, data + len);
    }

    void clear() {
        if (!_hasSrclr) throw std::runtime_error("SRCLR not configured");
        _clearCount++;
    }

    void set_output_enable(bool enabled) {
        if (!_hasG) throw std::runtime_error("G not configured");
        _outputEnableCalls.push_back(enabled);
    }

    void enable()  { _enabled = true; }
    void disable() { _enabled = false; }
    bool isEnabled() const { return _enabled; }

    const std::vector<std::vector<uint8_t>>& writes() const { return _writes; }
    int clearCount() const { return _clearCount; }
    const std::vector<bool>& outputEnableCalls() const { return _outputEnableCalls; }

private:
    bool _hasSrclr;
    bool _hasG;
    bool _enabled = true;
    int  _clearCount = 0;
    std::vector<std::vector<uint8_t>> _writes;
    std::vector<bool> _outputEnableCalls;
};
