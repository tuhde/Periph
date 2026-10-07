#pragma once
#include <stm32f4xx_hal.h>

/** @brief DHTxx single-wire connection for STM32Cube.
 *
 *  Implements the host side of the DHT11 / DHT22 single-wire protocol: a
 *  bidirectional DATA line, externally pulled up to VCC via a 4.7 kΩ
 *  resistor. Direction switching reconfigures the pin mode via
 *  `HAL_GPIO_Init()`; timing uses the same DWT cycle-counter microsecond
 *  delay as `HX711ConnectionSTM32Cube` (the STM32 HAL has no built-in
 *  microsecond delay).
 *
 *  This is a custom protocol with no generic byte read/write, so it does not
 *  extend the shared Connection base — it carries its own enabled flag
 *  directly, gating read().
 *
 *  @param port GPIO port for the DATA line.
 *  @param pin  GPIO pin for the DATA line.
 */
class DHTxxConnectionSTM32Cube {
public:
    DHTxxConnectionSTM32Cube(GPIO_TypeDef* port, uint16_t pin) : _port(port), _pin(pin) {
        _setInput();
    }

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
    bool read(uint8_t* out) {
        if (!_enabled) return false;
        // Drive low for 20 ms start pulse then release
        _setOutput();
        HAL_GPIO_WritePin(_port, _pin, GPIO_PIN_RESET);
        HAL_Delay(_START_LOW_MS);
        _setInput();

        // Sensor response: pull low ~80 µs, then high ~80 µs
        if (_measurePulse(GPIO_PIN_RESET, _RESPONSE_TIMEOUT_US) < 0) return false;
        if (_measurePulse(GPIO_PIN_SET, _RESPONSE_TIMEOUT_US) < 0) return false;

        for (uint8_t byte_idx = 0; byte_idx < 5; byte_idx++) {
            uint8_t byte = 0;
            for (uint8_t bit_idx = 0; bit_idx < 8; bit_idx++) {
                if (_measurePulse(GPIO_PIN_RESET, _BIT_TIMEOUT_US) < 0) return false;
                int32_t high = _measurePulse(GPIO_PIN_SET, _BIT_TIMEOUT_US);
                if (high < 0) return false;
                byte = (byte << 1) | (high > (int32_t)_BIT_THRESHOLD_US ? 1 : 0);
            }
            out[byte_idx] = byte;
        }
        return true;
    }

    /** @brief Release the pin back to input. */
    void close() { _setInput(); }

private:
    GPIO_TypeDef* _port;
    uint16_t       _pin;
    bool           _enabled = true;

    void _setInput() {
        GPIO_InitTypeDef gpioInit = {};
        gpioInit.Pin  = _pin;
        gpioInit.Mode = GPIO_MODE_INPUT;
        gpioInit.Pull = GPIO_NOPULL;
        HAL_GPIO_Init(_port, &gpioInit);
    }

    void _setOutput() {
        GPIO_InitTypeDef gpioInit = {};
        gpioInit.Pin   = _pin;
        gpioInit.Mode  = GPIO_MODE_OUTPUT_PP;
        gpioInit.Pull  = GPIO_NOPULL;
        gpioInit.Speed = GPIO_SPEED_FREQ_LOW;
        HAL_GPIO_Init(_port, &gpioInit);
    }

    int32_t _measurePulse(GPIO_PinState level, uint32_t timeout_us) {
        uint32_t start = _micros();
        while (HAL_GPIO_ReadPin(_port, _pin) != level) {
            if (_micros() - start > timeout_us) return -1;
        }
        uint32_t pulse_start = _micros();
        while (HAL_GPIO_ReadPin(_port, _pin) == level) {
            if (_micros() - pulse_start > timeout_us) return -1;
        }
        return static_cast<int32_t>(_micros() - pulse_start);
    }

    /** @brief Free-running microsecond counter via the Cortex-M4 DWT cycle counter. */
    static uint32_t _micros() {
        if (!(CoreDebug->DEMCR & CoreDebug_DEMCR_TRCENA_Msk)) {
            CoreDebug->DEMCR |= CoreDebug_DEMCR_TRCENA_Msk;
            DWT->CYCCNT = 0;
            DWT->CTRL |= DWT_CTRL_CYCCNTENA_Msk;
        }
        return DWT->CYCCNT / (SystemCoreClock / 1000000U);
    }

    static constexpr uint8_t  _START_LOW_MS        = 20;
    static constexpr uint32_t _RESPONSE_TIMEOUT_US = 200;
    static constexpr uint32_t _BIT_TIMEOUT_US      = 200;
    static constexpr uint32_t _BIT_THRESHOLD_US    = 40;
};
