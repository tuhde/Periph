#pragma once
#ifdef __linux__
#include <stdint.h>

struct gpiod_line_request;

/** @brief DHTxx single-wire connection for Linux (libgpiod v2).
 *
 *  Implements the host side of the DHT11 / DHT22 single-wire protocol: a
 *  bidirectional DATA line, externally pulled up to VCC via a 4.7 kΩ resistor.
 *  Direction switching requires releasing and re-requesting the line via the
 *  libgpiod v2 request lifecycle.
 *
 *  µs-level timing on a non-RTOS kernel is inherently imprecise under load.
 *  Read failures are expected on a busy system; callers should use the chip
 *  driver's retry mechanism rather than relying on single-shot reads.
 *
 *  Optional two-pin open-drain variant: pass `line_num_out` to request a
 *  second line wired to the same physical DATA net as open-drain output.
 *  The original line stays as input for the lifetime of the connection. This
 *  avoids the release/re-request entirely.
 *
 *  This is a custom protocol with no generic byte read/write, so it does not
 *  extend the shared Connection base — it carries its own enabled flag
 *  directly, gating read().
 *
 *  @param chip_path    Path to the gpiochip device (e.g. /dev/gpiochip0).
 *  @param line_num     GPIO line offset on that chip.
 *  @param line_num_out Optional second GPIO line offset (open-drain output).
 */
class DHTxxConnectionLinux {
public:
    DHTxxConnectionLinux(const char* chip_path, unsigned int line_num);
    DHTxxConnectionLinux(const char* chip_path, unsigned int line_num, unsigned int line_num_out);
    ~DHTxxConnectionLinux();

    /** @brief Resume reads. */
    void enable() { _enabled = true; }
    /** @brief Gate read(); it returns false without touching the bus while disabled. */
    void disable() { _enabled = false; }
    /** @brief Return the current software-gate state. */
    bool isEnabled() const { return _enabled; }

    /** @brief Execute the full DHTxx transaction and return the raw 5-byte frame.
     *
     *  @param out Pointer to a 5-byte buffer to receive the frame.
     *  @return     `true` on success, `false` on timeout/framing error/disabled.
     */
    bool read(uint8_t* out);

    /** @brief Release all held GPIO lines back to the kernel. */
    void close();

private:
    char*                    _chip_path;
    unsigned int             _line_num;
    unsigned int             _line_num_out;
    bool                     _two_pin;
    void*                    _chip;             // gpiod_chip*
    void*                    _input_req;        // gpiod_line_request*
    void*                    _output_req;       // gpiod_line_request*
    bool                     _closed;
    bool                     _enabled = true;

    void _drive_low();
    void _release_bus();
    int32_t _measure_pulse(int level, uint32_t timeout_us);

    static constexpr uint8_t  _START_LOW_MS        = 20;
    static constexpr uint32_t _RESPONSE_TIMEOUT_US = 200;
    static constexpr uint32_t _BIT_TIMEOUT_US      = 200;
    static constexpr uint32_t _BIT_THRESHOLD_US    = 40;
};
#endif // __linux__
