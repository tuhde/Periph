#pragma once
#include <string.h>
#include <stm32f4xx_hal.h>
#include "RegisterConnection.h"

/** @brief SPI connection for STM32Cube (STM32 HAL `stm32f4xx_hal_spi.h`).
 *
 * Bare-metal STM32Cube HAL. Constructor accepts an already-configured
 * `SPI_HandleTypeDef*`; the caller owns clock enable, GPIO AF muxing, and
 * `HAL_SPI_Init()` (typically CubeMX's `MX_SPI1_Init()`). CS is a plain
 * GPIO driven directly by this connection — same manual-CS convention as
 * `SPIConnectionPicoSDK`/`SPIConnectionLinux`, unlike Zephyr/ESP-IDF where
 * the driver owns CS.
 *
 * @param hspi         SPI handle, already initialised via `HAL_SPI_Init()`.
 * @param csPort       GPIO port for the CS line.
 * @param csPin        GPIO pin for the CS line (e.g. `GPIO_PIN_5`).
 * @param readBit      Bit ORed into the command byte for a read; 0 if the
 *                     chip has no such bit. Default 0x80.
 * @param multiByteBit Bit ORed in for multi-byte (burst) transfers when
 *                     len > 1; 0 if the chip has no such bit and always
 *                     auto-increments. Default 0.
 * @param intPin       Optional InputPin for INT-line delivery.
 * @param enPin        Optional OutputPin for hardware enable/power control.
 * @param enActiveHigh True if the EN pin is active-high (default); false for active-low.
 */
class SPIConnectionSTM32Cube : public RegisterConnection {
public:
    // See RegisterConnection.h's using declaration for why this is needed.
    using RegisterConnection::read;
    using RegisterConnection::write;

    SPIConnectionSTM32Cube(SPI_HandleTypeDef* hspi, GPIO_TypeDef* csPort, uint16_t csPin,
                           uint8_t readBit = 0x80, uint8_t multiByteBit = 0,
                           InputPin* intPin = nullptr, OutputPin* enPin = nullptr, bool enActiveHigh = true)
        : RegisterConnection(intPin, enPin, 1, enActiveHigh), _hspi(hspi), _csPort(csPort), _csPin(csPin),
          _readBit(readBit), _multiByteBit(multiByteBit) {
        HAL_GPIO_WritePin(_csPort, _csPin, GPIO_PIN_SET);  // CS idle HIGH (deasserted)
    }

    /** @brief Read @p len bytes starting at register @p reg, building the SPI command byte. */
    void read(uint32_t reg, uint8_t* buf, size_t len) override {
        uint8_t cmd = static_cast<uint8_t>(reg) | _readBit;
        if (len > 1 && _multiByteBit) cmd |= _multiByteBit;
        write_read(&cmd, 1, buf, len);
    }

    /** @brief Write @p len bytes of @p data to register @p reg, building the SPI command byte. */
    void write(uint32_t reg, const uint8_t* data, size_t len) override {
        uint8_t cmd = (static_cast<uint8_t>(reg) & static_cast<uint8_t>(~_readBit)) |
                      ((len > 1 && _multiByteBit) ? _multiByteBit : 0);
        uint8_t payload[17];
        payload[0] = cmd;
        memcpy(payload + 1, data, len);
        Connection::write(payload, len + 1);
    }

protected:
    /** @brief Send bytes to the device via `HAL_SPI_Transmit`, with manual CS.
     *  @param data Pointer to the data buffer.
     *  @param len  Number of bytes to send.
     */
    void _write(const uint8_t* data, size_t len) override {
        HAL_GPIO_WritePin(_csPort, _csPin, GPIO_PIN_RESET);
        HAL_SPI_Transmit(_hspi, const_cast<uint8_t*>(data), static_cast<uint16_t>(len), _TIMEOUT_MS);
        HAL_GPIO_WritePin(_csPort, _csPin, GPIO_PIN_SET);
    }

    /** @brief Read bytes from the device via `HAL_SPI_Receive`, with manual CS.
     *  @param buf Destination buffer; must be at least @p len bytes.
     *  @param len Number of bytes to read.
     */
    void _read(uint8_t* buf, size_t len) override {
        HAL_GPIO_WritePin(_csPort, _csPin, GPIO_PIN_RESET);
        HAL_SPI_Receive(_hspi, buf, static_cast<uint16_t>(len), _TIMEOUT_MS);
        HAL_GPIO_WritePin(_csPort, _csPin, GPIO_PIN_SET);
    }

    /** @brief Write then read with CS held low across both phases.
     *  @param data     Command bytes to send.
     *  @param data_len Number of bytes in @p data.
     *  @param buf      Destination buffer for the read phase.
     *  @param buf_len  Number of bytes to read.
     */
    void _write_read(const uint8_t* data, size_t data_len,
                     uint8_t* buf, size_t buf_len) override {
        HAL_GPIO_WritePin(_csPort, _csPin, GPIO_PIN_RESET);
        HAL_SPI_Transmit(_hspi, const_cast<uint8_t*>(data), static_cast<uint16_t>(data_len), _TIMEOUT_MS);
        HAL_SPI_Receive(_hspi, buf, static_cast<uint16_t>(buf_len), _TIMEOUT_MS);
        HAL_GPIO_WritePin(_csPort, _csPin, GPIO_PIN_SET);
    }

private:
    static constexpr uint32_t _TIMEOUT_MS = 1000;

    SPI_HandleTypeDef* _hspi;
    GPIO_TypeDef*       _csPort;
    uint16_t            _csPin;
    uint8_t             _readBit;
    uint8_t             _multiByteBit;   // 0 = chip has no such bit
};
