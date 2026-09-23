#pragma once
#ifdef __linux__
#include <stdint.h>
#include <stddef.h>
#include <memory>
#include "GpiodLineLinux.h"

/** @brief SiPo (serial-in/parallel-out shift register) connection for Linux GCC.
 *
 * Drives cascadable SIPO shift registers (TPIC6B595, SN74HC595, etc.) whose
 * SER IN/SRCK pins are electrically an SPI MOSI/SCK pair. Two constructors
 * are provided: one opens a hardware /dev/spidevBUS.DEVICE, the other
 * bit-bangs SER IN/SRCK as two libgpiod v2 lines. Either way, RCK — and, if
 * configured, SRCLR/G — are always plain GPIO lines, given as offsets on
 * one GPIO chip; -1 disables the optional SRCLR/G lines.
 *
 * Write-only: there is no read() or write_read(). This is a custom protocol
 * with no generic byte read/write, so it does not extend the shared
 * Connection base — it carries its own enabled flag directly, gating write().
 *
 * @param bus_num      SPI bus number (opens /dev/spidevBUS.DEVICE).
 * @param device_num   Chip-select line on the bus.
 * @param chip_path    GPIO chip device for RCK/SRCLR/G, e.g. "/dev/gpiochip0".
 * @param rck          Line offset for RCK.
 * @param srclr        Line offset for SRCLR; -1 (default) disables it.
 * @param g            Line offset for G; -1 (default) disables it.
 * @param max_speed_hz Clock frequency in Hz; default 1 000 000.
 */
class SiPoConnectionLinux {
public:
    SiPoConnectionLinux(int bus_num, int device_num,
                        const char* chip_path, unsigned int rck,
                        int srclr = -1, int g = -1,
                        uint32_t max_speed_hz = 1000000);

    /** @brief Bit-bang constructor: SER IN/SRCK are GPIO lines instead of a spidev device.
     *  @param chip_path GPIO chip device for every line, e.g. "/dev/gpiochip0".
     *  @param ser_in    Line offset for SER IN.
     *  @param srck      Line offset for SRCK.
     *  @param rck       Line offset for RCK.
     *  @param srclr     Line offset for SRCLR; -1 (default) disables it.
     *  @param g         Line offset for G; -1 (default) disables it.
     */
    SiPoConnectionLinux(const char* chip_path, unsigned int ser_in, unsigned int srck,
                        unsigned int rck, int srclr = -1, int g = -1);

    ~SiPoConnectionLinux();

    /** @brief Resume writes. */
    void enable() { _enabled = true; }
    /** @brief Gate write(); it becomes a no-op while disabled. */
    void disable() { _enabled = false; }
    /** @brief Return the current software-gate state. */
    bool isEnabled() const { return _enabled; }

    /** @brief Shift data out MSB-first, then latch it into the output register.
     *
     *  In hardware mode this transfers data over spidev; in software mode it
     *  bit-bangs SER IN/SRCK. Either way, RCK is then pulsed HIGH then LOW to
     *  latch the shifted data into the storage register that drives the
     *  outputs. No-op if this connection is disabled.
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
    std::unique_ptr<GpiodLineLinux> _ser_in;
    std::unique_ptr<GpiodLineLinux> _srck;
    std::unique_ptr<GpiodLineLinux> _rck;
    std::unique_ptr<GpiodLineLinux> _srclr;
    std::unique_ptr<GpiodLineLinux> _g;

    static std::unique_ptr<GpiodLineLinux> _output(const char* chip_path, int offset,
                                                   bool initial_high, const char* consumer);
    bool _enabled = true;

    void _latch();
};
#endif // __linux__
