#pragma once
#include <zephyr/kernel.h>
#include <zephyr/drivers/spi.h>
#include "DisplayConnectionBase.h"
#include "SoftSPI.h"

/** @brief Display connection (SPI + D/C#, RES#, BUSY) for Zephyr RTOS.
 *
 * Wraps the Zephyr `spi` driver API. Hardware vs. software SPI is a
 * devicetree choice (point `dev` at a real SPI controller or a
 * `spi-bitbang` node); a SoftSPI constructor is also provided for plain
 * GPIO bit-banging.
 *
 * Chip select: put the `spi_cs_control` in `config` so the driver owns CS#
 * (pass `cs = nullptr`), or pass an OutputPin to drive CS# here.
 *
 * Wire modes: kSimplex writes only. kHalfDuplexHW needs `SPI_HALF_DUPLEX` in
 * `config.operation` and a controller that supports it. kHalfDuplexPin needs a
 * full-duplex config (no `SPI_HALF_DUPLEX`) with MISO wired to the
 * controller's DIN/DOUT line (MOSI joined through a series resistor). A
 * config that contradicts the wire mode makes valid() false.
 *
 * prj.conf must enable CONFIG_SPI=y, CONFIG_GPIO=y, CONFIG_CPP=y,
 * CONFIG_STD_CPP17=y.
 *
 * @param dev    SPI controller device (hardware or spi-bitbang node).
 * @param config spi_config: clock, operation flags (mode 0, MSB-first, 8-bit).
 * @param cs     OutputPin for CS#, or nullptr when config.cs drives it.
 * @param dc     OutputPin for D/C# (low = command, high = data).
 * @param res    OutputPin for RES#, or nullptr if not wired.
 * @param busy   InputPin for BUSY, or nullptr if the controller has none.
 */
class DisplayConnectionZephyr : public DisplayConnectionBase {
public:
    DisplayConnectionZephyr(const struct device* dev, const struct spi_config& config,
                            OutputPin* cs, OutputPin* dc, OutputPin* res, InputPin* busy,
                            DisplayWireMode wireMode = DisplayWireMode::kSimplex,
                            uint32_t resetPulseUs = 200, uint32_t resetSettleMs = 10,
                            OutputPin* enPin = nullptr, bool enActiveHigh = true)
        : DisplayConnectionBase(cs, dc, res, busy, wireMode, resetPulseUs, resetSettleMs,
                                enPin, enActiveHigh),
          _dev(dev), _config(config), _soft(nullptr) {
        bool half = (config.operation & SPI_HALF_DUPLEX) != 0;
        if (wireMode == DisplayWireMode::kHalfDuplexHW && !half) invalidate();
        if (wireMode == DisplayWireMode::kHalfDuplexPin && half) invalidate();
    }

    /** @brief Software SPI constructor: bus is a bit-banged SoftSPI. */
    DisplayConnectionZephyr(SoftSPI& soft, OutputPin* cs, OutputPin* dc, OutputPin* res,
                            InputPin* busy,
                            DisplayWireMode wireMode = DisplayWireMode::kSimplex,
                            uint32_t resetPulseUs = 200, uint32_t resetSettleMs = 10,
                            OutputPin* enPin = nullptr, bool enActiveHigh = true)
        : DisplayConnectionBase(cs, dc, res, busy, wireMode, resetPulseUs, resetSettleMs,
                                enPin, enActiveHigh),
          _dev(nullptr), _config(), _soft(&soft) {
        if (wireMode == DisplayWireMode::kHalfDuplexHW) invalidate();
        if (wireMode == DisplayWireMode::kHalfDuplexPin && !soft.canRead()) invalidate();
    }

protected:
    void spiWrite(const uint8_t* data, size_t len) override {
        csLow();
        if (_soft != nullptr) {
            _soft->write(data, len);
        } else {
            struct spi_buf tx_buf = { .buf = const_cast<uint8_t*>(data), .len = len };
            struct spi_buf_set tx = { .buffers = &tx_buf, .count = 1 };
            spi_write(_dev, &_config, &tx);
        }
        csHigh();
    }

    void spiRead(uint8_t* buf, size_t len) override {
        csLow();
        if (_soft != nullptr) {
            _soft->read(buf, len);
        } else {
            struct spi_buf rx_buf = { .buf = buf, .len = len };
            struct spi_buf_set rx = { .buffers = &rx_buf, .count = 1 };
            spi_read(_dev, &_config, &rx);
        }
        csHigh();
    }

    void delayUs(uint32_t us) override { k_busy_wait(us); }
    void delayMs(uint32_t ms) override { k_msleep(ms); }
    uint32_t nowMs() override { return k_uptime_get_32(); }

private:
    const struct device* _dev;
    struct spi_config    _config;
    SoftSPI*             _soft;
};
