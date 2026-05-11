#pragma once
#include <Arduino.h>
#include <SPI.h>
#include "Transport.h"

/** @brief SPI transport for Arduino (wraps SPIClass).
 *
 * Uses beginTransaction/endTransaction for correct shared-bus operation.
 * CS idles high and is asserted low for the duration of each operation.
 *
 * @param bus      SPIClass instance to use (e.g., the global ::SPI).
 * @param cs_pin   CS pin number.
 * @param settings SPISettings bundling clock, bit order, and data mode.
 */
class SPITransport : public Transport {
public:
    SPITransport(SPIClass& bus, uint8_t cs_pin, SPISettings settings)
        : _bus(bus), _cs_pin(cs_pin), _settings(settings) {
        pinMode(_cs_pin, OUTPUT);
        digitalWrite(_cs_pin, HIGH);
    }

    /** @brief Assert CS, send bytes, deassert CS.
     *  @param data Pointer to the data buffer.
     *  @param len  Number of bytes to send.
     */
    void write(const uint8_t* data, size_t len) override;

    /** @brief Assert CS, clock out @p len dummy bytes, capture response, deassert CS.
     *  @param buf Destination buffer; must be at least @p len bytes.
     *  @param len Number of bytes to read.
     */
    void read(uint8_t* buf, size_t len) override;

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
    void write_read(const uint8_t* data, size_t data_len,
                    uint8_t* buf, size_t buf_len) override;

private:
    SPIClass&   _bus;
    uint8_t     _cs_pin;
    SPISettings _settings;
};
