#pragma once
#include <stdint.h>
#include <string.h>
#include <driver/spi_master.h>
#include <driver/rmt_tx.h>
#include "Connection.h"

/** @brief NeoPixel (WS2812B-compatible) connection for ESP-IDF.
 *
 * Two constructor modes — pick the one that matches your board and the
 * peripheral you want to use. Both produce the same on-the-wire signal;
 * which one a caller picks only affects which ESP32 peripheral and
 * CPU/DMA path is used.
 *
 * **SPI mode (default, consistent with every other platform):**
 * accepts an `spi_device_handle_t` already added to a bus via
 * `spi_bus_add_device()` at 2.4 MHz, mode 0. `write()` encodes the
 * buffer with the same 3-bit SPI encoding every other platform uses
 * (each NeoPixel bit → 3 SPI bits; "0" = 100, "1" = 110) and calls
 * `spi_device_polling_transmit()`. No CS pin is used.
 *
 * **RMT mode (native ESP32 WS2812 timing):** accepts an
 * `rmt_channel_handle_t` and an `rmt_encoder_handle_t` configured
 * with the WS2812 T0H/T0L/T1H/T1L timing — build the encoder with
 * `rmt_new_bytes_encoder()` and a `rmt_bytes_encoder_config_t`
 * specifying `bit0`/`bit1` symbol durations in RMT ticks, plus a
 * `reset_code` symbol of ≥50 µs low. `write()` calls
 * `rmt_transmit()` directly on the *unencoded* pixel bytes — the RMT
 * hardware peripheral does the bit-level encoding.
 *
 * Write-only: read()/write_read() are no-ops.
 *
 * Hardware constraint: the NeoPixel DIN pin must be connected to the
 * SPI MOSI pin in SPI mode (SCK, MISO, and CS are unused by the
 * strip); in RMT mode it must be connected to the RMT channel's TX
 * GPIO configured in `rmt_tx_channel_config_t.gpio_num`.
 *
 * Requires ESP-IDF ≥5.1 exported (`IDF_PATH` set, `idf.py` on PATH).
 * The consuming project must link `driver` (SPI mode) and `driver`
 * plus `rmt` (RMT mode).
 *
 * @param mode Selects between the SPI and RMT encoder. Pass
 *             `Mode::SPI` (default) for the SPI bit-encoding path,
 *             or `Mode::RMT` for the native RMT-peripheral path.
 */
class NeoPixelConnectionESPIDF : public Connection {
public:
    enum class Mode { SPI, RMT };

    /** @brief SPI mode constructor.
     *
     *  @param dev I2C device handle, already added to a bus via
     *             `spi_bus_add_device()` at 2.4 MHz, mode 0, MSB-first.
     *  @param intPin Optional InputPin (unused by NeoPixel; kept for API uniformity).
     *  @param enPin  Optional OutputPin for hardware enable/power control.
     */
    NeoPixelConnectionESPIDF(spi_device_handle_t dev, InputPin* intPin = nullptr, OutputPin* enPin = nullptr)
        : Connection(intPin, enPin), _mode(Mode::SPI), _spi(dev), _rmt_chan(nullptr), _encoder(nullptr) {}

    /** @brief RMT mode constructor.
     *
     *  @param rmt_chan RMT TX channel handle from `rmt_new_tx_channel()`.
     *  @param encoder  Bytes encoder handle from `rmt_new_bytes_encoder()`
     *                  with WS2812 T0H/T0L/T1H/T1L timings and a reset
     *                  code symbol of ≥50 µs low.
     *  @param intPin   Optional InputPin (unused by NeoPixel; kept for API uniformity).
     *  @param enPin    Optional OutputPin for hardware enable/power control.
     */
    NeoPixelConnectionESPIDF(rmt_channel_handle_t rmt_chan, rmt_encoder_handle_t encoder,
                             InputPin* intPin = nullptr, OutputPin* enPin = nullptr)
        : Connection(intPin, enPin), _mode(Mode::RMT), _spi(nullptr), _rmt_chan(rmt_chan), _encoder(encoder) {}

protected:
    /** @brief Encode and transmit pixel data, then hold the line low for reset.
     *
     *  In SPI mode, encodes `len` bytes into `3*len + 16` SPI bytes
     *  (the trailing 16 zero bytes provide the ≥50 µs reset pulse) and
     *  shifts them out via `spi_device_polling_transmit()`. In RMT mode
     *  hands the unencoded pixel bytes to the RMT encoder, which
     *  performs bit-level encoding and the reset pulse in hardware.
     *
     *  @param data Pointer to the pixel data buffer.
     *  @param len  Number of bytes to send (3 per RGB pixel, 4 per RGBW pixel).
     */
    void _write(const uint8_t* data, size_t len) override {
        if (_mode == Mode::SPI) {
            uint8_t encoded[3 * 64 + 16];
            if (len * 3 + 16 > sizeof(encoded)) {
                write_chunked(data, len);
                return;
            }
            encode(data, len, encoded);
            spi_transaction_t t = {};
            t.tx_buffer = encoded;
            t.length    = static_cast<size_t>(len) * 3 * 8 + 16 * 8;
            spi_device_polling_transmit(_spi, &t);
        } else {
            rmt_transmit_config_t tx_cfg = {};
            rmt_transmit(_rmt_chan, _encoder, const_cast<uint8_t*>(data),
                         len, &tx_cfg);
            rmt_tx_wait_all_done(_rmt_chan, -1);
        }
    }

    void _read(uint8_t* /*buf*/, size_t /*len*/) override {}

    void _write_read(const uint8_t* /*data*/, size_t /*data_len*/,
                     uint8_t* /*buf*/, size_t /*buf_len*/) override {}

private:
    Mode                 _mode;
    spi_device_handle_t  _spi;
    rmt_channel_handle_t _rmt_chan;
    rmt_encoder_handle_t _encoder;

    static void encode(const uint8_t* data, size_t len, uint8_t* out) {
        for (size_t i = 0; i < len; i++) {
            uint32_t bits = 0;
            for (int bit = 7; bit >= 0; bit--) {
                bits = (bits << 3) | ((data[i] >> bit) & 1 ? 0b110 : 0b100);
            }
            out[i * 3]     = (bits >> 16) & 0xFF;
            out[i * 3 + 1] = (bits >> 8)  & 0xFF;
            out[i * 3 + 2] =  bits        & 0xFF;
        }
        // 16 trailing zero bytes = ≥50 µs reset pulse at 2.4 MHz
        for (size_t i = len * 3; i < len * 3 + 16; i++) out[i] = 0;
    }

    void write_chunked(const uint8_t* data, size_t len) {
        constexpr size_t CHUNK = 64;
        uint8_t encoded[3 * CHUNK + 16];
        size_t pos = 0;
        while (pos < len) {
            size_t n = (len - pos < CHUNK) ? (len - pos) : CHUNK;
            bool last = (pos + n == len);
            encode(data + pos, n, encoded);
            spi_transaction_t t = {};
            t.tx_buffer = encoded;
            t.length    = static_cast<size_t>(n) * 3 * 8 + (last ? 16 * 8 : 0);
            spi_device_polling_transmit(_spi, &t);
            pos += n;
        }
    }
};
