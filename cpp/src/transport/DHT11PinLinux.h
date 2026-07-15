#pragma once
#ifdef __linux__
#include <stdint.h>

struct gpiod_chip;
struct gpiod_line;

/** @brief DHT11 pin adapter for Linux (libgpiod v1 lines).
 *
 *  Wraps a single libgpiod line that switches direction on every phase
 *  (re-requested as INPUT or OUTPUT). A 4.7 kΩ external pull-up to VCC is
 *  required on the DATA line.
 *
 *  @param chip  libgpiod chip handle (e.g. opened via ``gpiod_chip_open()``).
 *  @param line  Line offset on the chip.
 */
class DHT11PinLinux {
public:
    /** @brief Open the line, configure as INPUT, and store the handle.
     *
     *  @param chip  libgpiod chip handle.
     *  @param line  Line offset on the chip.
     */
    DHT11PinLinux(struct gpiod_chip* chip, unsigned int line);
    ~DHT11PinLinux();

    /** @brief Configure the line as output (host drives the bus). */
    void set_output();

    /** @brief Configure the line as input (host listens; pull-up holds HIGH). */
    void set_input();

    /** @brief Drive the line HIGH (``true``) or LOW (``false``).
     *
     *  Re-requests the line as OUTPUT if it is not already.
     */
    void drive(bool high);

    /** @brief Read the current logic level of the line.
     *  @return ``true`` for HIGH, ``false`` for LOW.
     */
    bool read();

    /** @brief Release the line back to the chip. */
    void close();

private:
    struct gpiod_chip*  _chip;
    struct gpiod_line*  _line;
    bool                _is_output;
};
#endif
