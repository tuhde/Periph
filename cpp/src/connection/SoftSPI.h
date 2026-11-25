#pragma once
#include <stdint.h>
#include <stddef.h>
#include "InputPin.h"
#include "OutputPin.h"

/** @brief Bit-banged SPI master (mode 0, MSB first) over the OutputPin/InputPin abstractions.
 *
 * Works on every platform that has GPIO, so it is the shared software-SPI
 * primitive for connections that offer a "software SPI" constructor. Chip
 * select is not part of the bus object: the connection drives CS itself.
 *
 * Reading needs the optional MISO input. For a display controller whose DIN
 * and DOUT share one pin, MISO is wired to that pin and MOSI is joined to it
 * through a series resistor, so the host keeps driving MOSI (dummy bytes)
 * while the chip drives the line and wins — no direction switch is needed.
 *
 * Header-only, no heap allocation, no exceptions.
 *
 * @param sck          OutputPin for the clock (idles low, mode 0).
 * @param mosi         OutputPin for the data line to the device.
 * @param miso         Optional InputPin sampling the data line from the device
 *                     (nullptr for a write-only bus).
 * @param delayUs      Optional platform microsecond delay called between clock
 *                     edges; nullptr runs as fast as the GPIO calls allow.
 * @param halfPeriodUs Delay passed to @p delayUs between edges, in microseconds.
 */
class SoftSPI {
public:
    typedef void (*DelayFn)(uint32_t us);

    SoftSPI(OutputPin* sck, OutputPin* mosi, InputPin* miso = nullptr,
            DelayFn delayUs = nullptr, uint32_t halfPeriodUs = 0)
        : _sck(sck), _mosi(mosi), _miso(miso), _delay(delayUs), _half(halfPeriodUs) {
        if (_sck != nullptr) _sck->set(false);
    }

    /** @brief True when a MISO pin was given, i.e. read() is available. */
    bool canRead() const { return _miso != nullptr; }

    /** @brief Shift @p len bytes out MSB-first, SPI mode 0. */
    void write(const uint8_t* data, size_t len) {
        for (size_t i = 0; i < len; i++) {
            for (int bit = 7; bit >= 0; bit--) {
                _mosi->set((data[i] >> bit) & 1);
                pause();
                _sck->set(true);
                pause();
                _sck->set(false);
            }
        }
    }

    /** @brief Clock in @p len bytes from MISO while sending @p fill on MOSI.
     *  @param buf  Destination buffer, at least @p len bytes.
     *  @param len  Number of bytes to read.
     *  @param fill Byte driven on MOSI during every clocked byte (default 0x00).
     *  @return false (nothing read) if no MISO pin was given.
     */
    bool read(uint8_t* buf, size_t len, uint8_t fill = 0x00) {
        if (_miso == nullptr) return false;
        for (size_t i = 0; i < len; i++) {
            uint8_t value = 0;
            for (int bit = 7; bit >= 0; bit--) {
                _mosi->set((fill >> bit) & 1);
                pause();
                _sck->set(true);
                pause();
                value = static_cast<uint8_t>((value << 1) | (_miso->isHigh() ? 1 : 0));
                _sck->set(false);
            }
            buf[i] = value;
        }
        return true;
    }

private:
    void pause() {
        if (_delay != nullptr && _half != 0) _delay(_half);
    }

    OutputPin* _sck;
    OutputPin* _mosi;
    InputPin*  _miso;
    DelayFn    _delay;
    uint32_t   _half;
};
