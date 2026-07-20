#pragma once
#ifdef __linux__
#include <stdint.h>
#include <stddef.h>

struct gpiod_line;

/** @brief SiPo (serial-in/parallel-out shift register) transport for Linux GCC.
 *
 * Drives cascadable SIPO shift registers (TPIC6B595, SN74HC595, etc.) whose
 * SER IN/SRCK pins are electrically an SPI MOSI/SCK pair. Two constructors
 * are provided: one opens a hardware /dev/spidevBUS.DEVICE, the other
 * bit-bangs SER IN/SRCK as two libgpiod lines. Either way, RCK — and, if
 * configured, SRCLR/G — are always plain libgpiod lines.
 *
 * Write-only: there is no read() or write_read().
 *
 * @param bus_num      SPI bus number (opens /dev/spidevBUS.DEVICE).
 * @param device_num   Chip-select line on the bus.
 * @param rck          libgpiod line requested as output, for RCK.
 * @param srclr        libgpiod line requested as output, for SRCLR; nullptr (default) disables it.
 * @param g            libgpiod line requested as output, for G; nullptr (default) disables it.
 * @param max_speed_hz Clock frequency in Hz; default 1 000 000.
 */
class SiPoTransportLinux {
public:
    SiPoTransportLinux(int bus_num, int device_num,
                       struct gpiod_line* rck,
                       struct gpiod_line* srclr = nullptr,
                       struct gpiod_line* g = nullptr,
                       uint32_t max_speed_hz = 1000000);

    /** @brief Bit-bang constructor: SER IN/SRCK are libgpiod lines instead of a spidev device.
     *  @param ser_in libgpiod line requested as output, for SER IN.
     *  @param srck   libgpiod line requested as output, for SRCK.
     *  @param rck    libgpiod line requested as output, for RCK.
     *  @param srclr  libgpiod line requested as output, for SRCLR; nullptr (default) disables it.
     *  @param g      libgpiod line requested as output, for G; nullptr (default) disables it.
     */
    SiPoTransportLinux(struct gpiod_line* ser_in, struct gpiod_line* srck,
                       struct gpiod_line* rck,
                       struct gpiod_line* srclr = nullptr,
                       struct gpiod_line* g = nullptr);

    ~SiPoTransportLinux();

    /** @brief Shift data out MSB-first, then latch it into the output register.
     *
     *  In hardware mode this transfers data over spidev; in software mode it
     *  bit-bangs SER IN/SRCK. Either way, RCK is then pulsed HIGH then LOW to
     *  latch the shifted data into the storage register that drives the
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
     *  @throws std::runtime_error if srclr was not configured.
     */
    void clear();

    /** @brief Drive G LOW (enabled) or HIGH (disabled).
     *
     *  @param enabled true drives G LOW, letting the storage register drive
     *         the outputs. false drives G HIGH, forcing every output off
     *         without disturbing the storage register's contents.
     *  @throws std::runtime_error if g was not configured.
     */
    void set_output_enable(bool enabled);

    /** @brief Release the spidev file descriptor (if opened) and all GPIO lines. */
    void close();

private:
    int      _fd;
    uint32_t _speed_hz;
    struct gpiod_line* _ser_in;
    struct gpiod_line* _srck;
    struct gpiod_line* _rck;
    struct gpiod_line* _srclr;
    struct gpiod_line* _g;

    void _latch();
};
#endif // __linux__
