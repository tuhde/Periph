#pragma once
#include <Arduino.h>
#include <SPI.h>
#include "RegisterConnection.h"

/** @brief SPI connection for Arduino (wraps SPIClass).
 *
 * Uses beginTransaction/endTransaction for correct shared-bus operation.
 * CS idles high and is asserted low for the duration of each operation.
 *
 * @param bus           SPIClass instance to use (e.g., the global ::SPI).
 * @param cs_pin        CS pin number.
 * @param settings      SPISettings bundling clock, bit order, and data mode.
 * @param readBit       Bit ORed into the command byte for a read; 0 if the
 *                      chip has no such bit. Default 0x80.
 * @param multiByteBit  Bit ORed in for multi-byte (burst) transfers when
 *                      len > 1; 0 if the chip has no such bit and always
 *                      auto-increments. Default 0.
 * @param intPin        Optional InputPin for INT-line delivery.
 * @param enPin         Optional OutputPin for hardware enable/power control.
 * @param enActiveHigh True if the EN pin is active-high (default); false for active-low.
 */
class SPIConnection : public RegisterConnection {
public:
    // See RegisterConnection.h's using declaration for why this is
    // needed: overriding read()/write() below would otherwise hide
    // RegisterConnection's (and, transitively, Connection's) other
    // read()/write() overloads.
    using RegisterConnection::read;
    using RegisterConnection::write;

    SPIConnection(SPIClass& bus, uint8_t cs_pin, SPISettings settings,
                  uint8_t readBit = 0x80, uint8_t multiByteBit = 0,
                  InputPin* intPin = nullptr, OutputPin* enPin = nullptr, bool enActiveHigh = true)
        : RegisterConnection(intPin, enPin, 1, enActiveHigh), _bus(bus), _cs_pin(cs_pin), _settings(settings),
          _readBit(readBit), _multiByteBit(multiByteBit) {
        pinMode(_cs_pin, OUTPUT);
        digitalWrite(_cs_pin, HIGH);
    }

    /** @brief Read @p len bytes starting at register @p reg, building the SPI command byte. */
    void read(uint32_t reg, uint8_t* buf, size_t len) override;

    /** @brief Write @p len bytes of @p data to register @p reg, building the SPI command byte. */
    void write(uint32_t reg, const uint8_t* data, size_t len) override;

protected:
    /** @brief Assert CS, send bytes, deassert CS.
     *  @param data Pointer to the data buffer.
     *  @param len  Number of bytes to send.
     */
    void _write(const uint8_t* data, size_t len) override;

    /** @brief Assert CS, clock out @p len dummy bytes, capture response, deassert CS.
     *  @param buf Destination buffer; must be at least @p len bytes.
     *  @param len Number of bytes to read.
     */
    void _read(uint8_t* buf, size_t len) override;

    /** @brief Assert CS, send command bytes, read response bytes, deassert CS.
     *
     *  Both phases execute within one beginTransaction/endTransaction block;
     *  CS stays low for the entire operation.
     *
     *  @param data     Command bytes to send.
     *  @param data_len Number of bytes in @p data.
     *  @param buf      Destination buffer for the read phase.
     *  @param buf_len  Number of bytes to read.
     */
    void _write_read(const uint8_t* data, size_t data_len,
                     uint8_t* buf, size_t buf_len) override;

private:
    SPIClass&   _bus;
    uint8_t     _cs_pin;
    SPISettings _settings;
    uint8_t     _readBit;
    uint8_t     _multiByteBit;   // 0 = chip has no such bit
};
