#pragma once
#include <Arduino.h>
#include <SPI.h>
#include "DisplayConnectionBase.h"
#include "SoftSPI.h"

/** @brief Display connection (SPI + D/C#, RES#, BUSY) for Arduino.
 *
 * Two constructors: one wraps a hardware SPIClass bus, the other uses a
 * SoftSPI (bit-banged) instance. CS# is an OutputPin driven manually: it idles
 * high and is asserted low for each SPI transfer.
 *
 * Wire modes: kSimplex needs only a write path. kHalfDuplexPin reads
 * full-duplex with MISO wired to the controller's DIN/DOUT line (MOSI joined to
 * it through a series resistor): SPIClass::transfer(0x00) or SoftSPI::read().
 * Arduino's SPIClass has no hardware 3-wire mode, so kHalfDuplexHW makes
 * valid() false.
 *
 * @param spi        SPIClass bus (e.g. the global ::SPI).
 * @param cs         OutputPin for CS# (active low).
 * @param dc         OutputPin for D/C# (low = command, high = data).
 * @param res        OutputPin for RES# (active low), or nullptr if not wired.
 * @param busy       InputPin for BUSY, or nullptr if the controller has none.
 * @param settings   SPISettings; default 4 MHz, MSB-first, mode 0.
 * @param wireMode   A DisplayWireMode value (default kSimplex).
 * @param resetPulseUs  RES# low time in microseconds (default 200).
 * @param resetSettleMs Wait after RES# is released in milliseconds (default 10).
 * @param enPin      Optional OutputPin for hardware enable/power control.
 * @param enActiveHigh True if the EN pin is active-high (default); false for active-low.
 */
class DisplayConnection : public DisplayConnectionBase {
public:
    DisplayConnection(SPIClass& spi, OutputPin* cs, OutputPin* dc, OutputPin* res, InputPin* busy,
                      SPISettings settings = SPISettings(4000000, MSBFIRST, SPI_MODE0),
                      DisplayWireMode wireMode = DisplayWireMode::kSimplex,
                      uint32_t resetPulseUs = 200, uint32_t resetSettleMs = 10,
                      OutputPin* enPin = nullptr, bool enActiveHigh = true)
        : DisplayConnectionBase(cs, dc, res, busy, wireMode, resetPulseUs, resetSettleMs,
                                enPin, enActiveHigh),
          _spi(&spi), _soft(nullptr), _settings(settings) {
        if (wireMode == DisplayWireMode::kHalfDuplexHW) invalidate();
    }

    /** @brief Software SPI constructor: bus is a bit-banged SoftSPI.
     *  kHalfDuplexPin needs a SoftSPI with a MISO pin; kHalfDuplexHW is invalid.
     */
    DisplayConnection(SoftSPI& soft, OutputPin* cs, OutputPin* dc, OutputPin* res, InputPin* busy,
                      DisplayWireMode wireMode = DisplayWireMode::kSimplex,
                      uint32_t resetPulseUs = 200, uint32_t resetSettleMs = 10,
                      OutputPin* enPin = nullptr, bool enActiveHigh = true)
        : DisplayConnectionBase(cs, dc, res, busy, wireMode, resetPulseUs, resetSettleMs,
                                enPin, enActiveHigh),
          _spi(nullptr), _soft(&soft), _settings(1000000, MSBFIRST, SPI_MODE0) {
        if (wireMode == DisplayWireMode::kHalfDuplexHW) invalidate();
        if (wireMode == DisplayWireMode::kHalfDuplexPin && !soft.canRead()) invalidate();
    }

protected:
    void spiWrite(const uint8_t* data, size_t len) override {
        csLow();
        if (_soft != nullptr) {
            _soft->write(data, len);
        } else {
            _spi->beginTransaction(_settings);
            for (size_t i = 0; i < len; i++) _spi->transfer(data[i]);
            _spi->endTransaction();
        }
        csHigh();
    }

    void spiRead(uint8_t* buf, size_t len) override {
        csLow();
        if (_soft != nullptr) {
            _soft->read(buf, len);
        } else {
            _spi->beginTransaction(_settings);
            for (size_t i = 0; i < len; i++) buf[i] = _spi->transfer(0x00);
            _spi->endTransaction();
        }
        csHigh();
    }

    void delayUs(uint32_t us) override {
        // delayMicroseconds() is only accurate up to 16383 us on AVR.
        while (us > 16000) { delayMicroseconds(16000); us -= 16000; }
        if (us > 0) delayMicroseconds(us);
    }
    void delayMs(uint32_t ms) override { delay(ms); }
    uint32_t nowMs() override { return millis(); }

private:
    SPIClass*   _spi;
    SoftSPI*    _soft;
    SPISettings _settings;
};
