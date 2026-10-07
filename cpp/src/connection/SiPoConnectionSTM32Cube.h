#pragma once
#include <errno.h>
#include <stm32f4xx_hal.h>

/** @brief SiPo (serial-in/parallel-out shift register) connection for STM32Cube.
 *
 * Drives cascadable SIPO shift registers (TPIC6B595, SN74HC595, etc.)
 * whose SER IN / SRCK pins are electrically an SPI MOSI / SCK pair. Two
 * constructor modes:
 *
 * - **Hardware:** pass an `SPI_HandleTypeDef*` plus RCK/SRCLR/G port+pin
 *   pairs. The connection shifts data via `HAL_SPI_Transmit` and latches
 *   with `HAL_GPIO_WritePin()` on RCK.
 * - **Software:** pass SER IN and SRCK port+pin pairs instead of an
 *   `SPI_HandleTypeDef*`. The connection bit-bangs the MSB-first mode-0
 *   loop directly with `HAL_GPIO_WritePin()`.
 *
 * Write-only: no `read` or `write_read` exists. This is a custom protocol
 * with no generic byte read/write, so it does not extend the shared
 * Connection base — it carries its own enabled flag directly, gating write().
 */
class SiPoConnectionSTM32Cube {
public:
    /** @brief Hardware SPI constructor.
     *
     *  @param hspi    SPI handle, already initialised via `HAL_SPI_Init()`
     *                 for 1 MHz, mode 0, MSB-first.
     *  @param rckPort GPIO port for RCK (register clock).
     *  @param rckPin  GPIO pin for RCK.
     *  @param srclrPort GPIO port for SRCLR; `nullptr` to disable.
     *  @param srclrPin  GPIO pin for SRCLR; ignored if @p srclrPort is `nullptr`.
     *  @param gPort     GPIO port for G (output enable); `nullptr` to disable.
     *  @param gPin      GPIO pin for G; ignored if @p gPort is `nullptr`.
     */
    SiPoConnectionSTM32Cube(SPI_HandleTypeDef* hspi, GPIO_TypeDef* rckPort, uint16_t rckPin,
                            GPIO_TypeDef* srclrPort = nullptr, uint16_t srclrPin = 0,
                            GPIO_TypeDef* gPort = nullptr, uint16_t gPin = 0)
        : _hspi(hspi), _serInPort(nullptr), _serInPin(0), _srckPort(nullptr), _srckPin(0),
          _rckPort(rckPort), _rckPin(rckPin), _srclrPort(srclrPort), _srclrPin(srclrPin),
          _gPort(gPort), _gPin(gPin), _useHwSpi(true) {
        _initGpio();
    }

    /** @brief Software (bit-bang) SPI constructor.
     *
     *  @param serInPort GPIO port for SER IN (MOSI equivalent).
     *  @param serInPin  GPIO pin for SER IN.
     *  @param srckPort  GPIO port for SRCK (SCK equivalent).
     *  @param srckPin   GPIO pin for SRCK.
     *  @param rckPort   GPIO port for RCK (register clock).
     *  @param rckPin    GPIO pin for RCK.
     *  @param srclrPort GPIO port for SRCLR; `nullptr` to disable.
     *  @param srclrPin  GPIO pin for SRCLR; ignored if @p srclrPort is `nullptr`.
     *  @param gPort     GPIO port for G (output enable); `nullptr` to disable.
     *  @param gPin      GPIO pin for G; ignored if @p gPort is `nullptr`.
     */
    SiPoConnectionSTM32Cube(GPIO_TypeDef* serInPort, uint16_t serInPin,
                            GPIO_TypeDef* srckPort, uint16_t srckPin,
                            GPIO_TypeDef* rckPort, uint16_t rckPin,
                            GPIO_TypeDef* srclrPort = nullptr, uint16_t srclrPin = 0,
                            GPIO_TypeDef* gPort = nullptr, uint16_t gPin = 0)
        : _hspi(nullptr), _serInPort(serInPort), _serInPin(serInPin),
          _srckPort(srckPort), _srckPin(srckPin), _rckPort(rckPort), _rckPin(rckPin),
          _srclrPort(srclrPort), _srclrPin(srclrPin), _gPort(gPort), _gPin(gPin), _useHwSpi(false) {
        _initGpio();
    }

    /** @brief Resume writes. */
    void enable() { _enabled = true; }
    /** @brief Gate write(); it becomes a no-op while disabled. */
    void disable() { _enabled = false; }
    /** @brief Return the current software-gate state. */
    bool isEnabled() const { return _enabled; }

    /** @brief Shift data out MSB-first, then latch it into the output register.
     *
     *  RCK is pulsed HIGH then LOW after the transfer to latch the
     *  shifted data into the storage register that drives the outputs.
     *  No-op if this connection is disabled.
     *
     *  @param data Pointer to the data buffer, one byte per cascaded device.
     *  @param len  Number of bytes to shift out.
     */
    void write(const uint8_t* data, size_t len) {
        if (!_enabled) return;
        if (_useHwSpi) {
            HAL_SPI_Transmit(_hspi, const_cast<uint8_t*>(data), static_cast<uint16_t>(len), 1000);
        } else {
            for (size_t i = 0; i < len; i++) {
                uint8_t byte = data[i];
                for (int bit = 7; bit >= 0; bit--) {
                    HAL_GPIO_WritePin(_serInPort, _serInPin, ((byte >> bit) & 1) ? GPIO_PIN_SET : GPIO_PIN_RESET);
                    HAL_GPIO_WritePin(_srckPort, _srckPin, GPIO_PIN_SET);
                    HAL_GPIO_WritePin(_srckPort, _srckPin, GPIO_PIN_RESET);
                }
            }
        }
        HAL_GPIO_WritePin(_rckPort, _rckPin, GPIO_PIN_SET);
        HAL_GPIO_WritePin(_rckPort, _rckPin, GPIO_PIN_RESET);
    }

    /** @brief Pulse SRCLR LOW then HIGH to clear the shift register.
     *
     *  The storage register (and therefore the outputs) is unaffected
     *  until the next `write()`.
     *
     *  @return 0 on success, `-ENODEV` if srclr was not configured.
     */
    int clear() {
        if (!_srclrPort) return -ENODEV;
        HAL_GPIO_WritePin(_srclrPort, _srclrPin, GPIO_PIN_RESET);
        HAL_GPIO_WritePin(_srclrPort, _srclrPin, GPIO_PIN_SET);
        return 0;
    }

    /** @brief Drive G LOW (enabled) or HIGH (disabled).
     *
     *  @param enabled true drives G LOW, letting the storage register
     *         drive the outputs. false drives G HIGH, forcing every
     *         output off without disturbing the storage register's
     *         contents.
     *  @return 0 on success, `-ENODEV` if g was not configured.
     */
    int set_output_enable(bool enabled) {
        if (!_gPort) return -ENODEV;
        HAL_GPIO_WritePin(_gPort, _gPin, enabled ? GPIO_PIN_RESET : GPIO_PIN_SET);
        return 0;
    }

private:
    SPI_HandleTypeDef* _hspi;
    GPIO_TypeDef*        _serInPort;
    uint16_t             _serInPin;
    GPIO_TypeDef*        _srckPort;
    uint16_t             _srckPin;
    GPIO_TypeDef*        _rckPort;
    uint16_t             _rckPin;
    GPIO_TypeDef*        _srclrPort;
    uint16_t             _srclrPin;
    GPIO_TypeDef*        _gPort;
    uint16_t             _gPin;
    bool                  _useHwSpi;
    bool                  _enabled = true;

    void _initGpio() {
        GPIO_InitTypeDef gpioInit = {};
        gpioInit.Mode  = GPIO_MODE_OUTPUT_PP;
        gpioInit.Pull  = GPIO_NOPULL;
        gpioInit.Speed = GPIO_SPEED_FREQ_HIGH;

        if (!_useHwSpi) {
            gpioInit.Pin = _serInPin;
            HAL_GPIO_Init(_serInPort, &gpioInit);
            HAL_GPIO_WritePin(_serInPort, _serInPin, GPIO_PIN_RESET);
            gpioInit.Pin = _srckPin;
            HAL_GPIO_Init(_srckPort, &gpioInit);
            HAL_GPIO_WritePin(_srckPort, _srckPin, GPIO_PIN_RESET);
        }
        gpioInit.Pin = _rckPin;
        HAL_GPIO_Init(_rckPort, &gpioInit);
        HAL_GPIO_WritePin(_rckPort, _rckPin, GPIO_PIN_RESET);

        if (_srclrPort) {
            gpioInit.Pin = _srclrPin;
            HAL_GPIO_Init(_srclrPort, &gpioInit);
            HAL_GPIO_WritePin(_srclrPort, _srclrPin, GPIO_PIN_SET);  // SRCLR idle HIGH (inactive)
        }
        if (_gPort) {
            gpioInit.Pin = _gPin;
            HAL_GPIO_Init(_gPort, &gpioInit);
            HAL_GPIO_WritePin(_gPort, _gPin, GPIO_PIN_RESET);  // G idle LOW (outputs enabled)
        }
    }
};
