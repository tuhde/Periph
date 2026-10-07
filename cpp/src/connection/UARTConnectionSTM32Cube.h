#pragma once
#include <stm32f4xx_hal.h>
#include "Connection.h"

/** @brief UART connection for STM32Cube (STM32 HAL `stm32f4xx_hal_uart.h`).
 *
 * Wraps the STM32 HAL's blocking UART API. Constructor accepts an
 * already-configured `UART_HandleTypeDef*` (caller owns clock enable,
 * GPIO AF muxing, and `HAL_UART_Init()`), plus an optional DE GPIO for
 * RS-485 mode — same convention as the Arduino/Linux/ESP-IDF/Pico SDK
 * UART connections.
 *
 * The 1000 ms timeout matches every other platform's UART connection in
 * this repo. `HAL_UART_Transmit` already blocks until the data is
 * actually clocked out (unlike ESP-IDF's FIFO-and-return
 * `uart_write_bytes`), so DE is deasserted immediately after it returns,
 * with no separate "wait for TX done" call needed.
 *
 * This repo's STM32Cube connections are polling-only (no DMA/interrupt,
 * per `specs/feature_stm32cube_platform.md` §2), so there is no
 * background RX buffer to report a fill level for — `available()` keeps
 * `Connection`'s default (`SIZE_MAX`, "unknown, assume ready"), unlike
 * ESP-IDF's driver-level RX FIFO.
 *
 * @param huart  UART handle, already initialised via `HAL_UART_Init()`.
 * @param dePort GPIO port for the RS-485 DE line; `nullptr` disables RS-485 mode.
 * @param dePin  GPIO pin for the RS-485 DE line; ignored if @p dePort is `nullptr`.
 * @param intPin Optional InputPin for INT-line delivery.
 * @param enPin  Optional OutputPin for hardware enable/power control.
 * @param enActiveHigh True if the EN pin is active-high (default); false for active-low.
 */
class UARTConnectionSTM32Cube : public Connection {
public:
    UARTConnectionSTM32Cube(UART_HandleTypeDef* huart, GPIO_TypeDef* dePort = nullptr, uint16_t dePin = 0,
                            InputPin* intPin = nullptr, OutputPin* enPin = nullptr, bool enActiveHigh = true)
        : Connection(intPin, enPin, enActiveHigh), _huart(huart), _dePort(dePort), _dePin(dePin)
    {
        if (_dePort) HAL_GPIO_WritePin(_dePort, _dePin, GPIO_PIN_RESET);  // DE idles LOW (receive enabled)
    }

protected:
    /** @brief Transmit bytes; in RS-485 mode assert DE around the transfer.
     *  @param data Pointer to the data buffer.
     *  @param len  Number of bytes to send.
     */
    void _write(const uint8_t* data, size_t len) override {
        if (_dePort) HAL_GPIO_WritePin(_dePort, _dePin, GPIO_PIN_SET);
        HAL_UART_Transmit(_huart, const_cast<uint8_t*>(data), static_cast<uint16_t>(len), _TIMEOUT_MS);
        if (_dePort) HAL_GPIO_WritePin(_dePort, _dePin, GPIO_PIN_RESET);
    }

    /** @brief Receive @p len bytes; blocks up to a 1000 ms total timeout.
     *  @param buf Destination buffer; must be at least @p len bytes.
     *  @param len Number of bytes to read.
     */
    void _read(uint8_t* buf, size_t len) override {
        HAL_UART_Receive(_huart, buf, static_cast<uint16_t>(len), _TIMEOUT_MS);
    }

    /** @brief Transmit bytes then receive @p buf_len bytes. DE, if wired,
     *  is asserted only during the transmit phase.
     *  @param data     Command bytes to send.
     *  @param data_len Number of bytes in @p data.
     *  @param buf      Destination buffer for the read phase.
     *  @param buf_len  Number of bytes to read.
     */
    void _write_read(const uint8_t* data, size_t data_len,
                     uint8_t* buf, size_t buf_len) override {
        _write(data, data_len);
        _read(buf, buf_len);
    }

private:
    static constexpr uint32_t _TIMEOUT_MS = 1000;

    UART_HandleTypeDef* _huart;
    GPIO_TypeDef*         _dePort;
    uint16_t              _dePin;
};
