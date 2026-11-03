#pragma once
#include <stdint.h>
#include <driver/spi_master.h>
#include <esp_timer.h>
#include <esp_rom_sys.h>
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
#include "DisplayConnectionBase.h"
#include "SoftSPI.h"

/** @brief Display connection (SPI + D/C#, RES#, BUSY) for ESP-IDF.
 *
 * Wraps an `spi_device_handle_t` (driver-ng `driver/spi_master.h`) already
 * added to a bus via `spi_bus_add_device()` at the desired clock, mode 0. A
 * SoftSPI constructor is also provided for plain GPIO bit-banging.
 *
 * Chip select: the SPI driver owns CS# when the device was added with a
 * `spics_io_num` (pass `cs = nullptr`); otherwise pass an OutputPin.
 *
 * Wire modes: kSimplex writes only. kHalfDuplexHW needs the device added with
 * `SPI_DEVICE_3WIRE` (DIN/DOUT on the MOSI pin); the handle cannot be
 * inspected, so the caller guarantees it. kHalfDuplexPin reads full-duplex
 * with MISO wired to the controller's DIN/DOUT line (MOSI joined through a
 * series resistor).
 *
 * Transfers larger than @p maxTransferBytes are split into several
 * transactions (the default bus configuration allows 4092 bytes); with
 * driver-owned CS each chunk is its own CS# window. Increase
 * `max_transfer_sz` in `spi_bus_config_t` and pass a larger value to send a
 * whole pixel plane in one transaction.
 *
 * The consuming project must `REQUIRES esp_driver_spi esp_driver_gpio esp_timer`.
 */
class DisplayConnectionESPIDF : public DisplayConnectionBase {
public:
    DisplayConnectionESPIDF(spi_device_handle_t spi, OutputPin* cs, OutputPin* dc, OutputPin* res,
                            InputPin* busy,
                            DisplayWireMode wireMode = DisplayWireMode::kSimplex,
                            size_t maxTransferBytes = 4092,
                            uint32_t resetPulseUs = 200, uint32_t resetSettleMs = 10,
                            OutputPin* enPin = nullptr, bool enActiveHigh = true)
        : DisplayConnectionBase(cs, dc, res, busy, wireMode, resetPulseUs, resetSettleMs,
                                enPin, enActiveHigh),
          _spi(spi), _soft(nullptr), _maxChunk(maxTransferBytes ? maxTransferBytes : 4092) {}

    /** @brief Software SPI constructor: bus is a bit-banged SoftSPI. */
    DisplayConnectionESPIDF(SoftSPI& soft, OutputPin* cs, OutputPin* dc, OutputPin* res,
                            InputPin* busy,
                            DisplayWireMode wireMode = DisplayWireMode::kSimplex,
                            uint32_t resetPulseUs = 200, uint32_t resetSettleMs = 10,
                            OutputPin* enPin = nullptr, bool enActiveHigh = true)
        : DisplayConnectionBase(cs, dc, res, busy, wireMode, resetPulseUs, resetSettleMs,
                                enPin, enActiveHigh),
          _spi(nullptr), _soft(&soft), _maxChunk(4092) {
        if (wireMode == DisplayWireMode::kHalfDuplexHW) invalidate();
        if (wireMode == DisplayWireMode::kHalfDuplexPin && !soft.canRead()) invalidate();
    }

protected:
    void spiWrite(const uint8_t* data, size_t len) override {
        csLow();
        if (_soft != nullptr) {
            _soft->write(data, len);
        } else {
            for (size_t off = 0; off < len; off += _maxChunk) {
                size_t n = (len - off < _maxChunk) ? (len - off) : _maxChunk;
                spi_transaction_t t = {};
                t.length    = n * 8;
                t.tx_buffer = data + off;
                spi_device_polling_transmit(_spi, &t);
            }
        }
        csHigh();
    }

    void spiRead(uint8_t* buf, size_t len) override {
        csLow();
        if (_soft != nullptr) {
            _soft->read(buf, len);
        } else {
            for (size_t off = 0; off < len; off += _maxChunk) {
                size_t n = (len - off < _maxChunk) ? (len - off) : _maxChunk;
                spi_transaction_t t = {};
                t.length    = n * 8;
                t.rxlength  = n * 8;
                t.rx_buffer = buf + off;
                spi_device_polling_transmit(_spi, &t);
            }
        }
        csHigh();
    }

    void delayUs(uint32_t us) override { esp_rom_delay_us(us); }
    void delayMs(uint32_t ms) override { vTaskDelay(pdMS_TO_TICKS(ms) ? pdMS_TO_TICKS(ms) : 1); }
    uint32_t nowMs() override { return static_cast<uint32_t>(esp_timer_get_time() / 1000); }

private:
    spi_device_handle_t _spi;
    SoftSPI*            _soft;
    size_t              _maxChunk;
};
