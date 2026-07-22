#pragma once
#include <zephyr/drivers/gpio.h>
#include <zephyr/kernel.h>

/** @brief DHTxx single-wire transport for Zephyr RTOS.
 *
 *  Implements the host side of the DHT11 / DHT22 single-wire protocol: a
 *  bidirectional DATA line, externally pulled up to VCC via a 4.7 kΩ resistor.
 *  Direction switching uses `gpio_pin_configure_dt`; timing uses
 *  `k_busy_wait()` for the start pulse and `k_cycle_get_32()` for pulse-width
 *  measurement.
 *
 *  prj.conf must enable `CONFIG_GPIO=y`, `CONFIG_CPP=y`, `CONFIG_STD_CPP17=y`.
 *
 *  @param spec GPIO devicetree spec for the DATA pin.
 */
class DHTxxTransportZephyr {
public:
    /** @brief Construct the transport and configure the pin as input.
     *  @param spec GPIO devicetree spec for the DATA line.
     */
    explicit DHTxxTransportZephyr(const struct gpio_dt_spec& spec)
        : _spec(spec) {
        gpio_pin_configure_dt(&_spec, GPIO_INPUT);
    }

    /** @brief Execute the full DHTxx transaction and return the raw 5-byte frame.
     *
     *  @param out Pointer to a 5-byte buffer to receive the frame.
     *  @return     `true` on success, `false` on timeout/framing error.
     */
    bool read(uint8_t* out) {
        gpio_pin_configure_dt(&_spec, GPIO_OUTPUT_ACTIVE);
        k_busy_wait(_START_LOW_MS * 1000);
        gpio_pin_configure_dt(&_spec, GPIO_INPUT);

        int32_t elapsed = _measure_pulse(0, _RESPONSE_TIMEOUT_US);
        if (elapsed < 0) return false;
        elapsed = _measure_pulse(1, _RESPONSE_TIMEOUT_US);
        if (elapsed < 0) return false;

        for (uint8_t byte_idx = 0; byte_idx < 5; byte_idx++) {
            uint8_t byte = 0;
            for (uint8_t bit_idx = 0; bit_idx < 8; bit_idx++) {
                elapsed = _measure_pulse(0, _BIT_TIMEOUT_US);
                if (elapsed < 0) return false;
                elapsed = _measure_pulse(1, _BIT_TIMEOUT_US);
                if (elapsed < 0) return false;
                byte = (byte << 1) | (elapsed > (int32_t)_BIT_THRESHOLD_US ? 1 : 0);
            }
            out[byte_idx] = byte;
        }
        return true;
    }

private:
    const struct gpio_dt_spec _spec;

    int32_t _measure_pulse(int level, uint32_t timeout_us) {
        uint32_t start = k_cycle_get_32();
        uint32_t timeout_cyc = k_us_to_cyc_near32(timeout_us);
        while (gpio_pin_get_dt(&_spec) != level) {
            if (k_cycle_get_32() - start > timeout_cyc) return -1;
        }
        uint32_t pulse_start = k_cycle_get_32();
        while (gpio_pin_get_dt(&_spec) == level) {
            if (k_cycle_get_32() - pulse_start > timeout_cyc) return -1;
        }
        return (int32_t)k_cyc_to_us_near32(k_cycle_get_32() - pulse_start);
    }

    static constexpr uint8_t  _START_LOW_MS        = 20;
    static constexpr uint32_t _RESPONSE_TIMEOUT_US = 200;
    static constexpr uint32_t _BIT_TIMEOUT_US      = 200;
    static constexpr uint32_t _BIT_THRESHOLD_US    = 40;
};
