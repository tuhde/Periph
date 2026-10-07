#pragma once
#include <stm32f4xx_hal.h>
#include "RegisterConnection.h"

/** @brief I²C connection for STM32Cube (STM32 HAL `stm32f4xx_hal_i2c.h`).
 *
 * Bare-metal STM32Cube HAL — no RTOS, no devicetree. Constructor accepts
 * an already-configured `I2C_HandleTypeDef*`; the caller owns clock
 * enable, GPIO AF muxing, and `HAL_I2C_Init()` (typically CubeMX's
 * `MX_I2C1_Init()`), the same division of responsibility ESP-IDF's
 * "caller creates the bus, constructor just wraps the handle" and Pico
 * SDK's "caller calls `i2c_init()`" conventions use.
 *
 * `_write_read` issues `HAL_I2C_Master_Transmit` immediately followed by
 * `HAL_I2C_Master_Receive` — **not** a true repeated START. The STM32 HAL
 * blocking/polling I²C API has no generic repeated-start primitive for an
 * arbitrary-length write phase (only `HAL_I2C_Mem_Read`, which is limited
 * to a 1- or 2-byte register address and isn't a fit for this connection's
 * generic byte-buffer contract); a brief STOP/START pair is issued
 * between the two phases instead. Every chip driver in this repo re-reads
 * the just-written register address rather than relying on an
 * auto-incrementing pointer across unrelated writes, so this has not
 * caused a correctness issue on any platform tested so far — but it is a
 * real difference from Zephyr/ESP-IDF/Pico SDK's true repeated start,
 * worth knowing if a future chip needs one.
 *
 * Requires an STM32Cube HAL project (`STM32CUBE_FW_PATH`, see
 * `TOOLCHAINS.md`) with `HAL_I2C_MODULE_ENABLED` in `stm32f4xx_hal_conf.h`.
 *
 * @param hi2c      I²C handle, already initialised via `HAL_I2C_Init()`.
 * @param addr      7-bit I²C device address.
 * @param intPin    Optional InputPin for INT-line delivery.
 * @param enPin     Optional OutputPin for hardware enable/power control.
 * @param regBytes  Register address width in bytes, big-endian (default 1).
 * @param enActiveHigh True if the EN pin is active-high (default); false for active-low.
 */
class I2CConnectionSTM32Cube : public RegisterConnection {
public:
    I2CConnectionSTM32Cube(I2C_HandleTypeDef* hi2c, uint8_t addr, InputPin* intPin = nullptr,
                           OutputPin* enPin = nullptr, uint8_t regBytes = 1, bool enActiveHigh = true)
        : RegisterConnection(intPin, enPin, regBytes, enActiveHigh), _hi2c(hi2c), _addr(addr) {}

protected:
    /** @brief Send bytes to the device via `HAL_I2C_Master_Transmit`.
     *  @param data Pointer to the data buffer.
     *  @param len  Number of bytes to send.
     */
    void _write(const uint8_t* data, size_t len) override {
        HAL_I2C_Master_Transmit(_hi2c, static_cast<uint16_t>(_addr << 1),
                                const_cast<uint8_t*>(data), static_cast<uint16_t>(len), _TIMEOUT_MS);
    }

    /** @brief Read bytes from the device via `HAL_I2C_Master_Receive`.
     *  @param buf Destination buffer; must be at least @p len bytes.
     *  @param len Number of bytes to read.
     */
    void _read(uint8_t* buf, size_t len) override {
        HAL_I2C_Master_Receive(_hi2c, static_cast<uint16_t>(_addr << 1),
                               buf, static_cast<uint16_t>(len), _TIMEOUT_MS);
    }

    /** @brief Write then read; see the class comment for why this is a
     *  STOP/START pair rather than a true repeated start.
     *  @param data     Register/command bytes to send.
     *  @param data_len Number of bytes in @p data.
     *  @param buf      Destination buffer for the read phase.
     *  @param buf_len  Number of bytes to read.
     */
    void _write_read(const uint8_t* data, size_t data_len,
                     uint8_t* buf, size_t buf_len) override {
        _write(data, data_len);
        _read(buf, buf_len);
    }

    /** @brief Underlying I²C handle, exposed so derived connections
     *         (e.g. SMBus with PEC) can issue raw transfers that the
     *         public write/read/write_read API does not cover. */
    I2C_HandleTypeDef* hal_handle() const { return _hi2c; }
    /** @brief 7-bit I²C address this connection was constructed with. */
    uint8_t i2c_address() const { return _addr; }

private:
    static constexpr uint32_t _TIMEOUT_MS = 1000;

    I2C_HandleTypeDef* _hi2c;
    uint8_t             _addr;
};
