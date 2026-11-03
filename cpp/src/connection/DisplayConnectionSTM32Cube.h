#pragma once
#include <stm32f4xx_hal.h>
#include "DisplayConnectionBase.h"
#include "SoftSPI.h"

/** @brief Display connection (SPI + D/C#, RES#, BUSY) for STM32Cube (HAL, polling).
 *
 * Wraps an `SPI_HandleTypeDef*` already initialised with `HAL_SPI_Init()`
 * (master, 8-bit, mode 0, MSB-first). CS# is an OutputPin driven here
 * (OutputPinSTM32Cube). A SoftSPI constructor is also provided for plain GPIO
 * bit-banging.
 *
 * Wire modes are checked against `hspi->Init.Direction`:
 * - kSimplex: any direction that can transmit.
 * - kHalfDuplexHW: requires `SPI_DIRECTION_1LINE` (DIN/DOUT on the MOSI pin);
 *   the HAL turns the line around inside HAL_SPI_Transmit/Receive.
 * - kHalfDuplexPin: requires `SPI_DIRECTION_2LINES` with MISO wired to the
 *   controller's DIN/DOUT line (MOSI joined through a series resistor).
 * A mismatch makes valid() false.
 *
 * Delays below 1 ms busy-wait on the Cortex-M4 DWT cycle counter (the HAL has
 * no microsecond delay); longer ones use HAL_Delay().
 *
 * @param hspi Initialised SPI handle.
 */
class DisplayConnectionSTM32Cube : public DisplayConnectionBase {
public:
    DisplayConnectionSTM32Cube(SPI_HandleTypeDef* hspi, OutputPin* cs, OutputPin* dc, OutputPin* res,
                               InputPin* busy,
                               DisplayWireMode wireMode = DisplayWireMode::kSimplex,
                               uint32_t resetPulseUs = 200, uint32_t resetSettleMs = 10,
                               OutputPin* enPin = nullptr, bool enActiveHigh = true)
        : DisplayConnectionBase(cs, dc, res, busy, wireMode, resetPulseUs, resetSettleMs,
                                enPin, enActiveHigh),
          _hspi(hspi), _soft(nullptr) {
        bool oneLine = hspi->Init.Direction == SPI_DIRECTION_1LINE;
        if (wireMode == DisplayWireMode::kHalfDuplexHW && !oneLine) invalidate();
        if (wireMode == DisplayWireMode::kHalfDuplexPin && oneLine) invalidate();
    }

    /** @brief Software SPI constructor: bus is a bit-banged SoftSPI. */
    DisplayConnectionSTM32Cube(SoftSPI& soft, OutputPin* cs, OutputPin* dc, OutputPin* res,
                               InputPin* busy,
                               DisplayWireMode wireMode = DisplayWireMode::kSimplex,
                               uint32_t resetPulseUs = 200, uint32_t resetSettleMs = 10,
                               OutputPin* enPin = nullptr, bool enActiveHigh = true)
        : DisplayConnectionBase(cs, dc, res, busy, wireMode, resetPulseUs, resetSettleMs,
                                enPin, enActiveHigh),
          _hspi(nullptr), _soft(&soft) {
        if (wireMode == DisplayWireMode::kHalfDuplexHW) invalidate();
        if (wireMode == DisplayWireMode::kHalfDuplexPin && !soft.canRead()) invalidate();
    }

protected:
    void spiWrite(const uint8_t* data, size_t len) override {
        csLow();
        if (_soft != nullptr) {
            _soft->write(data, len);
        } else {
            // HAL sizes are uint16_t: split larger transfers.
            for (size_t off = 0; off < len;) {
                uint16_t n = (len - off > 0xFFFF) ? 0xFFFF : static_cast<uint16_t>(len - off);
                HAL_SPI_Transmit(_hspi, const_cast<uint8_t*>(data) + off, n, kTimeoutMs);
                off += n;
            }
        }
        csHigh();
    }

    void spiRead(uint8_t* buf, size_t len) override {
        csLow();
        if (_soft != nullptr) {
            _soft->read(buf, len);
        } else if (_hspi->Init.Direction == SPI_DIRECTION_1LINE) {
            for (size_t off = 0; off < len;) {
                uint16_t n = (len - off > 0xFFFF) ? 0xFFFF : static_cast<uint16_t>(len - off);
                HAL_SPI_Receive(_hspi, buf + off, n, kTimeoutMs);
                off += n;
            }
        } else {
            // 2-line: clock zeros out while reading MISO, in place.
            for (size_t off = 0; off < len;) {
                uint16_t n = (len - off > 0xFFFF) ? 0xFFFF : static_cast<uint16_t>(len - off);
                for (uint16_t i = 0; i < n; i++) buf[off + i] = 0x00;
                HAL_SPI_TransmitReceive(_hspi, buf + off, buf + off, n, kTimeoutMs);
                off += n;
            }
        }
        csHigh();
    }

    void delayUs(uint32_t us) override {
        if (us >= 1000) { HAL_Delay((us + 999) / 1000); return; }
        if (!(CoreDebug->DEMCR & CoreDebug_DEMCR_TRCENA_Msk)) {
            CoreDebug->DEMCR |= CoreDebug_DEMCR_TRCENA_Msk;
            DWT->CYCCNT = 0;
            DWT->CTRL |= DWT_CTRL_CYCCNTENA_Msk;
        }
        uint32_t cycles = (SystemCoreClock / 1000000U) * us;
        uint32_t start = DWT->CYCCNT;
        while ((DWT->CYCCNT - start) < cycles) {}
    }
    void delayMs(uint32_t ms) override { HAL_Delay(ms); }
    uint32_t nowMs() override { return HAL_GetTick(); }

private:
    static constexpr uint32_t kTimeoutMs = 1000;

    SPI_HandleTypeDef* _hspi;
    SoftSPI*           _soft;
};
