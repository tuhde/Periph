#pragma once
#include <stm32f4xx_hal.h>
#include <climits>

/** @brief HX711 GPIO bit-bang connection for STM32Cube.
 *
 * Implements the 2-wire bit-bang protocol used exclusively by the HX711
 * 24-bit ADC. DOUT is sampled on each falling edge of PD_SCK; the pulse
 * count selects the channel and gain for the next conversion.
 *
 * The caller owns clock enable for both GPIO ports; this class configures
 * DOUT as input and PD_SCK as push-pull output at construction.
 *
 * Timing: the per-edge ~1 µs delays (to meet the HX711's 200 ns T3/T4
 * minimums) use the Cortex-M4 DWT cycle counter — the STM32 HAL has no
 * built-in microsecond delay (`HAL_Delay()` is millisecond-granular
 * only), unlike Pico SDK's `busy_wait_us()`/ESP-IDF's `esp_rom_delay_us()`.
 * The 1 ms poll interval during the 1 s ready-wait uses `HAL_Delay()`.
 *
 * This is a custom protocol with no generic byte read/write, so it does not
 * extend the shared Connection base — it carries its own enabled flag
 * directly, gating read_raw().
 *
 * @param doutPort Port for the DOUT line (data input).
 * @param doutPin  Pin for the DOUT line.
 * @param sckPort  Port for the PD_SCK line (clock / power-down).
 * @param sckPin   Pin for the PD_SCK line.
 */
class HX711ConnectionSTM32Cube {
public:
    HX711ConnectionSTM32Cube(GPIO_TypeDef* doutPort, uint16_t doutPin,
                             GPIO_TypeDef* sckPort, uint16_t sckPin)
        : _doutPort(doutPort), _doutPin(doutPin), _sckPort(sckPort), _sckPin(sckPin)
    {
        GPIO_InitTypeDef gpioInit = {};
        gpioInit.Pin  = _doutPin;
        gpioInit.Mode = GPIO_MODE_INPUT;
        gpioInit.Pull = GPIO_NOPULL;
        HAL_GPIO_Init(_doutPort, &gpioInit);

        gpioInit.Pin   = _sckPin;
        gpioInit.Mode  = GPIO_MODE_OUTPUT_PP;
        gpioInit.Pull  = GPIO_NOPULL;
        gpioInit.Speed = GPIO_SPEED_FREQ_HIGH;
        HAL_GPIO_Init(_sckPort, &gpioInit);
        HAL_GPIO_WritePin(_sckPort, _sckPin, GPIO_PIN_RESET);  // PD_SCK idle LOW
    }

    /** @brief Resume conversions. */
    void enable() { _enabled = true; }
    /** @brief Gate read_raw(); it returns 0 without touching the bus while disabled. */
    void disable() { _enabled = false; }
    /** @brief Return the current software-gate state. */
    bool isEnabled() const { return _enabled; }

    /** @brief Return true if a conversion result is available (DOUT is LOW).
     *  @return true when DOUT is LOW (data ready).
     */
    bool is_ready() {
        return HAL_GPIO_ReadPin(_doutPort, _doutPin) == GPIO_PIN_RESET;
    }

    /** @brief Wait up to 1 s for data ready, then clock out a conversion.
     *
     *  Polls DOUT until LOW (conversion ready), then sends exactly
     *  @p num_pulses pulses on PD_SCK, sampling DOUT at each falling
     *  edge (HIGH→LOW transition). Leaves PD_SCK LOW after the last
     *  pulse. The pulse count programs the channel and gain for the
     *  next conversion:
     *  25 → Channel A Gain 128, 26 → Channel B Gain 32, 27 → Channel A Gain 64.
     *  Returns 0 without touching the bus if this connection is disabled.
     *
     *  @param num_pulses Number of PD_SCK pulses (must be 25, 26, or 27).
     *  @return           Signed 24-bit ADC value, 0 if disabled, or
     *                    INT32_MIN on timeout or invalid pulse count.
     */
    int32_t read_raw(uint8_t num_pulses = 25) {
        if (!_enabled) return 0;
        if (num_pulses != 25 && num_pulses != 26 && num_pulses != 27)
            return INT32_MIN;
        uint32_t deadline = HAL_GetTick() + 1000;
        while (HAL_GPIO_ReadPin(_doutPort, _doutPin) != GPIO_PIN_RESET) {
            if (HAL_GetTick() >= deadline) return INT32_MIN;
        }
        uint32_t raw = 0;
        for (uint8_t i = 0; i < num_pulses; i++) {
            HAL_GPIO_WritePin(_sckPort, _sckPin, GPIO_PIN_SET);
            _delay_us(1);
            HAL_GPIO_WritePin(_sckPort, _sckPin, GPIO_PIN_RESET);
            _delay_us(1);
            raw = (raw << 1) | static_cast<uint32_t>(HAL_GPIO_ReadPin(_doutPort, _doutPin));
        }
        raw >>= num_pulses - 24;
        if (raw & 0x800000u)
            return static_cast<int32_t>(raw) - 0x1000000;
        return static_cast<int32_t>(raw);
    }

    /** @brief Enter power-down mode by holding PD_SCK HIGH for >60 µs. */
    void power_down() {
        HAL_GPIO_WritePin(_sckPort, _sckPin, GPIO_PIN_SET);
        _delay_us(65);
    }

    /** @brief Exit power-down mode and reset the chip.
     *
     *  Drives PD_SCK LOW. The chip resets to Channel A, Gain 128. The
     *  first conversion after power-up must be discarded.
     */
    void power_up() {
        HAL_GPIO_WritePin(_sckPort, _sckPin, GPIO_PIN_RESET);
    }

private:
    GPIO_TypeDef* _doutPort;
    uint16_t       _doutPin;
    GPIO_TypeDef* _sckPort;
    uint16_t       _sckPin;
    bool           _enabled = true;

    /** @brief Busy-wait @p us microseconds using the Cortex-M4 DWT cycle counter. */
    static void _delay_us(uint32_t us) {
        if (!(CoreDebug->DEMCR & CoreDebug_DEMCR_TRCENA_Msk)) {
            CoreDebug->DEMCR |= CoreDebug_DEMCR_TRCENA_Msk;
            DWT->CYCCNT = 0;
            DWT->CTRL |= DWT_CTRL_CYCCNTENA_Msk;
        }
        uint32_t cycles = (SystemCoreClock / 1000000U) * us;
        uint32_t start = DWT->CYCCNT;
        while ((DWT->CYCCNT - start) < cycles) {}
    }
};
