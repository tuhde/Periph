#pragma once
#ifdef __linux__

struct gpiod_chip;
struct gpiod_line_request;

/** @brief One GPIO line requested through libgpiod v2 (Linux GCC).
 *
 *  Owns the chip handle and the line request and releases both on
 *  destruction. Shared by the Linux GPIO connections (InputPinLinux,
 *  OutputPinLinux, HX711ConnectionLinux, SiPoConnectionLinux) so they all
 *  address lines the same way DHTxxConnectionLinux does: a chip path such
 *  as "/dev/gpiochip0" plus the line offset shown by gpioinfo.
 *
 *  Non-copyable; construction failures leave the line invalid (valid()
 *  returns false) and make get()/set() no-ops.
 */
class GpiodLineLinux {
public:
    /** @brief Line direction and bias. */
    enum class Mode { Input, InputPullUp, Output };

    /** @brief Request a line.
     *  @param chip_path    GPIO chip device, e.g. "/dev/gpiochip0".
     *  @param offset       Line offset on that chip.
     *  @param mode         Input, input with pull-up, or output.
     *  @param initial_high Initial level for outputs (ignored for inputs).
     *  @param consumer     Consumer label shown by gpioinfo.
     */
    GpiodLineLinux(const char* chip_path, unsigned int offset, Mode mode,
                   bool initial_high = false, const char* consumer = "periph");
    ~GpiodLineLinux();

    GpiodLineLinux(const GpiodLineLinux&) = delete;
    GpiodLineLinux& operator=(const GpiodLineLinux&) = delete;

    /** @brief True if the line was requested successfully. */
    bool valid() const { return _request != nullptr; }

    /** @brief Read the line level: 1 = high, 0 = low, -1 = error. */
    int get();

    /** @brief Drive an output line high or low. */
    void set(bool high);

    /** @brief Release the line and close the chip (idempotent). */
    void release();

private:
    struct gpiod_chip*         _chip;
    struct gpiod_line_request* _request;
    unsigned int               _offset;
};

#endif // __linux__
