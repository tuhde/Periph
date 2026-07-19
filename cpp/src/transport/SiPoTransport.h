#pragma once
#include <Arduino.h>
#include <SPI.h>

/** @brief SiPo (serial-in/parallel-out shift register) transport for Arduino.
 *
 * Drives cascadable SIPO shift registers (TPIC6B595, SN74HC595, etc.) whose
 * SER IN/SRCK pins are electrically an SPI MOSI/SCK pair. Two constructors
 * are provided: one wraps a hardware SPIClass bus, the other bit-bangs
 * SER IN/SRCK as two plain pins. Either way, RCK — and, if configured,
 * SRCLR/G — are always plain digitalWrite() pins.
 *
 * Write-only: there is no read() or write_read().
 *
 * @param spi        SPIClass bus to use (e.g., the global ::SPI).
 * @param rck_pin    Pin number for RCK (register clock).
 * @param srclr_pin  Pin number for SRCLR; -1 (default) disables it.
 * @param g_pin      Pin number for G (output enable); -1 (default) disables it.
 * @param settings   SPISettings bundling clock, bit order, and data mode; default 1 MHz, MSB-first, mode 0.
 */
class SiPoTransport {
public:
    SiPoTransport(SPIClass& spi, int rck_pin, int srclr_pin = -1, int g_pin = -1,
                 SPISettings settings = SPISettings(1000000, MSBFIRST, SPI_MODE0));

    /** @brief Bit-bang constructor: SER IN/SRCK are plain pins instead of a hardware SPI bus.
     *  @param ser_in_pin Pin number for SER IN (serial data).
     *  @param srck_pin   Pin number for SRCK (shift register clock).
     *  @param rck_pin    Pin number for RCK (register clock).
     *  @param srclr_pin  Pin number for SRCLR; -1 (default) disables it.
     *  @param g_pin      Pin number for G (output enable); -1 (default) disables it.
     */
    SiPoTransport(int ser_in_pin, int srck_pin, int rck_pin,
                 int srclr_pin = -1, int g_pin = -1);

    /** @brief Shift data out MSB-first, then latch it into the output register.
     *
     *  In hardware mode this transfers data over SPIClass; in software mode
     *  it bit-bangs SER IN/SRCK. Either way, RCK is then pulsed HIGH then LOW
     *  to latch the shifted data into the storage register that drives the
     *  outputs.
     *
     *  @param data Pointer to the data buffer, one byte per cascaded device.
     *  @param len  Number of bytes to shift out.
     */
    void write(const uint8_t* data, size_t len);

    /** @brief Pulse SRCLR LOW then HIGH to clear the shift register.
     *
     *  The storage register (and therefore the outputs) is unaffected until
     *  the next write().
     *
     *  @return false if srclr_pin was not configured (-1), true otherwise.
     */
    bool clear();

    /** @brief Drive G LOW (enabled) or HIGH (disabled).
     *
     *  @param enabled true drives G LOW, letting the storage register drive
     *         the outputs. false drives G HIGH, forcing every output off
     *         without disturbing the storage register's contents.
     *  @return false if g_pin was not configured (-1), true otherwise.
     */
    bool set_output_enable(bool enabled);

private:
    SPIClass*   _spi;
    SPISettings _settings;
    int _ser_in;
    int _srck;
    int _rck;
    int _srclr;
    int _g;

    void _latch();
};
