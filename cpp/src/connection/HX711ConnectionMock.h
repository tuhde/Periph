#pragma once
#include <stdint.h>
#include <stdexcept>
#include <vector>
#include <deque>
#include <string>

/** @brief In-memory fake HX711 connection for unit tests — no hardware, no GPIO.
 *
 *  HX711 has no register map; each conversion is instead selected by the
 *  pulse count (25/26/27) sent to read_raw(). Preload signed 24-bit
 *  conversion results with queueRead(); each read_raw() call pops the next
 *  one (0 if the queue is empty) and, matching every real connection's
 *  contract, validates num_pulses is one of {25, 26, 27}, throwing
 *  std::invalid_argument otherwise. Every call's pulse count is appended to
 *  reads() so tests can assert which channel/gain a driver call actually
 *  requested (e.g. that set_gain() drives the right pulse count).
 *  power_down()/power_up() calls are logged to powerCalls().
 *
 *  This is a plain duck-typed mock, not a Connection subclass — HX711Minimal
 *  / HX711Full are templates over Connection with no virtual interface, so
 *  any type with matching method signatures plugs in directly.
 */
class HX711ConnectionMock {
public:
    HX711ConnectionMock() = default;

    /** @brief Queue a signed 24-bit value to be returned by the next read_raw(). */
    void queueRead(int32_t value) { _queue.push_back(value); }

    /** @brief Pulse count of every read_raw() call, in call order. */
    const std::vector<uint8_t>& reads() const { return _reads; }

    /** @brief Log of power_down()/power_up() calls ("down"/"up"), in call order. */
    const std::vector<std::string>& powerCalls() const { return _powerCalls; }

    /** @brief Settable ready state returned by is_ready(). */
    bool ready = true;

    bool is_ready() { return ready; }

    int32_t read_raw(uint8_t num_pulses) {
        if (num_pulses != 25 && num_pulses != 26 && num_pulses != 27)
            throw std::invalid_argument("num_pulses must be 25, 26, or 27");
        _reads.push_back(num_pulses);
        if (!_queue.empty()) {
            int32_t v = _queue.front();
            _queue.pop_front();
            return v;
        }
        return 0;
    }

    void power_down() { _powerCalls.push_back("down"); }
    void power_up() { _powerCalls.push_back("up"); }

private:
    std::deque<int32_t> _queue;
    std::vector<uint8_t> _reads;
    std::vector<std::string> _powerCalls;
};
