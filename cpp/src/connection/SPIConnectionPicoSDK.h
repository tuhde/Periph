#pragma once
#include <hardware/spi.h>
#include <hardware/gpio.h>
#include "Connection.h"

/** @brief SPI connection for the Raspberry Pi Pico SDK (wraps `hardware_spi`).
 *
 * Bare-metal `pico-sdk` — no Arduino core, no RTOS, no devicetree. One
 * connection instance represents one device on one SPI controller; the
 * controller is initialised by the caller via `spi_init()` and the GPIO
 * pin functions via `gpio_set_function()` before being passed in.
 *
 * `pico-sdk` has no automatic CS the way Zephyr's devicetree `cs-gpios`
 * does, so CS is a plain GPIO the connection drives itself — the same
 * convention `SPIConnection` (Arduino) already uses.
 *
 * `write_read` shifts the command bytes and `buf_len` dummy bytes in one
 * CS-asserted transaction using `spi_write_read_blocking`, then discards
 * the first `data_len` received bytes (the chip's response during the
 * command phase).
 *
 * Requires the `PICO_SDK_PATH` environment variable and `pico_sdk_init()`
 * in the consuming CMake project. Link against `hardware_spi` and
 * `hardware_gpio`.
 *
 * @param spi    SPI controller pointer (`spi0` or `spi1`).
 * @param cs     GPIO pin number used for chip-select (active LOW).
 * @param intPin Optional InputPin for INT-line delivery.
 * @param enPin  Optional OutputPin for hardware enable/power control.
 */
class SPIConnectionPicoSDK : public Connection {
public:
    SPIConnectionPicoSDK(spi_inst_t* spi, uint cs, InputPin* intPin = nullptr, OutputPin* enPin = nullptr)
        : Connection(intPin, enPin), _spi(spi), _cs(cs)
    {
        gpio_init(_cs);
        gpio_set_dir(_cs, GPIO_OUT);
        gpio_put(_cs, 1);  // CS idles high
    }

protected:
    /** @brief Send bytes to the device.
     *  @param data Pointer to the data buffer.
     *  @param len  Number of bytes to send.
     */
    void _write(const uint8_t* data, size_t len) override {
        gpio_put(_cs, 0);
        spi_write_blocking(_spi, data, len);
        gpio_put(_cs, 1);
    }

    /** @brief Read bytes from the device (dummy `0x00` clocked out on MOSI).
     *  @param buf Destination buffer; must be at least @p len bytes.
     *  @param len Number of bytes to read.
     */
    void _read(uint8_t* buf, size_t len) override {
        gpio_put(_cs, 0);
        spi_read_blocking(_spi, 0x00, buf, len);
        gpio_put(_cs, 1);
    }

    /** @brief Write then read with CS held across both phases.
     *
     *  Uses `spi_write_read_blocking` to shift `data_len + buf_len` bytes
     *  in a single CS-asserted transaction, then discards the first
     *  `data_len` received bytes (the chip's response during the command
     *  phase).
     *
     *  @param data     Command bytes to send.
     *  @param data_len Number of bytes in @p data.
     *  @param buf      Destination buffer for the read phase.
     *  @param buf_len  Number of bytes to read.
     */
    void _write_read(const uint8_t* data, size_t data_len,
                     uint8_t* buf, size_t buf_len) override {
        // Combine command bytes and a zeroed dummy buffer into one transfer.
        // pico-sdk's `spi_write_read_blocking` requires the source and dest
        // buffers to be the same length, so we shift `data_len + buf_len`
        // bytes total in one CS-asserted transaction.
        uint8_t tx_buf[256] = {};   // 256 covers every chip driver's largest payload
        for (size_t i = 0; i < data_len; i++) tx_buf[i] = data[i];
        // tx_buf[data_len .. data_len+buf_len-1] stays zero — dummy bytes
        // clocked out on MOSI during the read phase.
        uint8_t rx_buf[256];
        gpio_put(_cs, 0);
        spi_write_read_blocking(_spi, tx_buf, rx_buf, data_len + buf_len);
        gpio_put(_cs, 1);
        for (size_t i = 0; i < buf_len; i++) buf[i] = rx_buf[data_len + i];
    }

private:
    spi_inst_t* _spi;
    uint        _cs;
};
