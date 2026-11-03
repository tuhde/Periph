#pragma once
#include <hardware/spi.h>
#include <pico/time.h>
#include "DisplayConnectionBase.h"
#include "SoftSPI.h"

/** @brief Display connection (SPI + D/C#, RES#, BUSY) for the Raspberry Pi Pico SDK.
 *
 * Wraps an `spi_inst_t*` (`spi0` / `spi1`) already configured with
 * `spi_init()`, mode 0, MSB-first, 8 bits. CS# is an OutputPin driven here.
 * A SoftSPI constructor is also provided for plain GPIO bit-banging.
 *
 * Wire modes: kSimplex writes only. kHalfDuplexPin reads full-duplex with MISO
 * wired to the controller's DIN/DOUT line (MOSI joined through a series
 * resistor) via `spi_read_blocking()`. The PL022 SPI block has no 3-wire mode,
 * so kHalfDuplexHW makes valid() false.
 *
 * Link `hardware_spi` and `pico_stdlib`.
 */
class DisplayConnectionPicoSDK : public DisplayConnectionBase {
public:
    DisplayConnectionPicoSDK(spi_inst_t* spi, OutputPin* cs, OutputPin* dc, OutputPin* res,
                             InputPin* busy,
                             DisplayWireMode wireMode = DisplayWireMode::kSimplex,
                             uint32_t resetPulseUs = 200, uint32_t resetSettleMs = 10,
                             OutputPin* enPin = nullptr, bool enActiveHigh = true)
        : DisplayConnectionBase(cs, dc, res, busy, wireMode, resetPulseUs, resetSettleMs,
                                enPin, enActiveHigh),
          _spi(spi), _soft(nullptr) {
        if (wireMode == DisplayWireMode::kHalfDuplexHW) invalidate();
    }

    /** @brief Software SPI constructor: bus is a bit-banged SoftSPI. */
    DisplayConnectionPicoSDK(SoftSPI& soft, OutputPin* cs, OutputPin* dc, OutputPin* res,
                             InputPin* busy,
                             DisplayWireMode wireMode = DisplayWireMode::kSimplex,
                             uint32_t resetPulseUs = 200, uint32_t resetSettleMs = 10,
                             OutputPin* enPin = nullptr, bool enActiveHigh = true)
        : DisplayConnectionBase(cs, dc, res, busy, wireMode, resetPulseUs, resetSettleMs,
                                enPin, enActiveHigh),
          _spi(nullptr), _soft(&soft) {
        if (wireMode == DisplayWireMode::kHalfDuplexHW) invalidate();
        if (wireMode == DisplayWireMode::kHalfDuplexPin && !soft.canRead()) invalidate();
    }

protected:
    void spiWrite(const uint8_t* data, size_t len) override {
        csLow();
        if (_soft != nullptr) _soft->write(data, len);
        else spi_write_blocking(_spi, data, len);
        csHigh();
    }

    void spiRead(uint8_t* buf, size_t len) override {
        csLow();
        if (_soft != nullptr) _soft->read(buf, len);
        else spi_read_blocking(_spi, 0x00, buf, len);
        csHigh();
    }

    void delayUs(uint32_t us) override { sleep_us(us); }
    void delayMs(uint32_t ms) override { sleep_ms(ms); }
    uint32_t nowMs() override { return to_ms_since_boot(get_absolute_time()); }

private:
    spi_inst_t* _spi;
    SoftSPI*    _soft;
};
