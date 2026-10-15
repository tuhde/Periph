#pragma once
#include <stdint.h>
#include <stddef.h>
#include "InputPin.h"
#include "OutputPin.h"

/** @brief How the display controller's data pin is wired to the host.
 *
 *  - kSimplex:       MOSI -> DIN, write only; readData() is not available.
 *  - kHalfDuplexHW:  DIN/DOUT shared with MOSI, read through the host SPI
 *                    peripheral's hardware 3-wire (half-duplex) mode; only on
 *                    platforms that have one.
 *  - kHalfDuplexPin: MOSI -- resistor -- MISO joined at DIN/DOUT, read through
 *                    a full-duplex (hardware or software) SPI master that
 *                    samples MISO.
 */
enum class DisplayWireMode : uint8_t {
    kSimplex = 0,
    kHalfDuplexHW = 1,
    kHalfDuplexPin = 2,
};

/** @brief Platform-independent part of the display connection (SPI + D/C#, RES#, BUSY).
 *
 * Bundles an SPI link to a display controller (OLED: SH1106; e-paper: SSD1683,
 * UC8276C; ...) with its side-band lines: D/C# (command/data), RES# (hardware
 * reset) and BUSY. RES# and BUSY are optional (an OLED has no BUSY line and may
 * tie RES# high): pass nullptr. Chip drivers call sendCommand() / sendData() /
 * readData() / reset() / waitBusy() and never touch D/C, RES, BUSY or CS.
 *
 * The BUSY polarity differs between controllers, so it is chip-level
 * knowledge: waitBusy() takes it as an argument and busyLevel() returns the
 * raw level.
 *
 * Chip select is hardware CS only: every sendCommand() / sendData() /
 * readData() byte group is its own SPI transfer framed by CS; a command and
 * its parameters are separate CS windows and readData() uses one CS window per
 * byte, as the controllers' read timing diagrams show.
 *
 * No exceptions: an unsupported configuration (for example kHalfDuplexHW on a
 * platform without a 3-wire mode) makes valid() return false and turns every
 * bus operation into a no-op.
 *
 * Platform classes implement spiWrite(), spiRead(), delayUs(), delayMs() and
 * nowMs(). Header-only, no heap allocation.
 *
 * @param cs           OutputPin for CS# (active low) when CS is driven here;
 *                     nullptr when the SPI driver owns CS.
 * @param dc           OutputPin for D/C# (low = command, high = data).
 * @param res          OutputPin for RES# (active low), or nullptr if not wired.
 * @param busy         InputPin for BUSY read with isHigh(), or nullptr if the
 *                     controller has no BUSY line.
 * @param wireMode     A DisplayWireMode value.
 * @param resetPulseUs RES# low time in microseconds (default 200, which meets
 *                     the UC8276C 50 us and SSD1683 200 us minimums).
 * @param resetSettleMs Wait after RES# is released in milliseconds (default 10).
 * @param enPin        Optional OutputPin for hardware enable/power control.
 * @param enActiveHigh True if the EN pin is active-high (default); false for active-low.
 */
class DisplayConnectionBase {
public:
    /** @brief Pass as the timeout to waitBusy() to wait without a limit. */
    static constexpr uint32_t kWaitForever = 0xFFFFFFFFu;

    DisplayConnectionBase(OutputPin* cs, OutputPin* dc, OutputPin* res, InputPin* busy,
                          DisplayWireMode wireMode = DisplayWireMode::kSimplex,
                          uint32_t resetPulseUs = 200, uint32_t resetSettleMs = 10,
                          OutputPin* enPin = nullptr, bool enActiveHigh = true)
        : _cs(cs), _dc(dc), _res(res), _busy(busy), _wireMode(wireMode),
          _resetPulseUs(resetPulseUs), _resetSettleMs(resetSettleMs),
          _enPin(enPin), _enActiveHigh(enActiveHigh) {
        if (_dc == nullptr) _valid = false;   // D/C# is the one mandatory side-band line
        if (_cs != nullptr)  _cs->set(true);
        if (_dc != nullptr)  _dc->set(false);
        if (_res != nullptr) _res->set(true);
    }

    virtual ~DisplayConnectionBase() = default;

    /** @brief False when the configuration is unsupported on this platform. */
    bool valid() const { return _valid; }

    /** @brief The configured wire mode. */
    DisplayWireMode wireMode() const { return _wireMode; }

    /** @brief Resume operation; asserts the hardware EN pin if wired. */
    void enable() {
        _enabled = true;
        if (_enPin != nullptr) _enPin->set(_enActiveHigh);
    }
    /** @brief Gate all bus operations; de-asserts the hardware EN pin if wired. */
    void disable() {
        _enabled = false;
        if (_enPin != nullptr) _enPin->set(!_enActiveHigh);
    }
    /** @brief Return the current software-gate state. */
    bool isEnabled() const { return _enabled; }

    /** @brief Hardware reset: RES# low for resetPulseUs, then high and wait resetSettleMs.
     *
     *  Does not wait for BUSY; chip drivers call waitBusy() afterwards with their
     *  polarity. No-op when disabled, invalid, or no RES# pin was given.
     */
    void reset() {
        if (!_enabled || !_valid || _res == nullptr) return;
        _res->set(false);
        delayUs(_resetPulseUs);
        _res->set(true);
        delayMs(_resetSettleMs);
    }

    /** @brief Block for @p ms milliseconds (for chips without a BUSY pin, e.g. a power-up wait). */
    void delay(uint32_t ms) { delayMs(ms); }

    /** @brief Raw level of the BUSY line; false when no BUSY pin was given. */
    bool busyLevel() const {
        return _busy != nullptr && _busy->isHigh();
    }

    /** @brief Poll BUSY until it is no longer active.
     *
     *  @param busyActiveHigh True if the controller drives BUSY high while busy
     *         (SSD1683), false if it drives it low (UC8276C BUSY_N).
     *  @param timeoutMs Give up after this many milliseconds; kWaitForever waits
     *         without limit (default).
     *  @return true when the controller became ready or has no BUSY pin; false on timeout.
     */
    bool waitBusy(bool busyActiveHigh, uint32_t timeoutMs = kWaitForever) {
        if (_busy == nullptr) return true;
        uint32_t start = nowMs();
        while (_busy->isHigh() == busyActiveHigh) {
            if (timeoutMs != kWaitForever && static_cast<uint32_t>(nowMs() - start) >= timeoutMs)
                return false;
            delayMs(1);
        }
        return true;
    }

    /** @brief Send a command byte (D/C# low), then optional parameter bytes (D/C# high).
     *
     *  The command byte and the parameters are separate SPI transfers (separate
     *  CS windows). No-op if disabled or invalid.
     *
     *  @param cmd  Command byte.
     *  @param data Parameter bytes, or nullptr for none.
     *  @param len  Number of parameter bytes.
     */
    void sendCommand(uint8_t cmd, const uint8_t* data = nullptr, size_t len = 0) {
        if (!_enabled || !_valid) return;
        _dc->set(false);
        spiWrite(&cmd, 1);
        if (data != nullptr && len > 0) {
            _dc->set(true);
            spiWrite(data, len);
        }
    }

    /** @brief Send data bytes (D/C# high) as one SPI transfer. No-op if disabled or invalid.
     *  @param data Bytes to send, e.g. a pixel plane.
     *  @param len  Number of bytes.
     */
    void sendData(const uint8_t* data, size_t len) {
        if (!_enabled || !_valid || len == 0) return;
        _dc->set(true);
        spiWrite(data, len);
    }

    /** @brief Read @p len bytes the controller drives after a read command (D/C# high).
     *
     *  One CS window per byte. A disabled connection fills @p buf with zeros.
     *
     *  @param buf Destination buffer, at least @p len bytes.
     *  @param len Number of bytes to read.
     *  @return false in kSimplex wire mode or when invalid (nothing read); true otherwise.
     */
    bool readData(uint8_t* buf, size_t len) {
        if (!_valid || _wireMode == DisplayWireMode::kSimplex) return false;
        if (!_enabled) {
            for (size_t i = 0; i < len; i++) buf[i] = 0;
            return true;
        }
        _dc->set(true);
        for (size_t i = 0; i < len; i++) spiRead(&buf[i], 1);
        return true;
    }

protected:
    /** @brief Send bytes as one CS-framed SPI transfer. */
    virtual void spiWrite(const uint8_t* data, size_t len) = 0;
    /** @brief Clock in bytes as one CS-framed SPI transfer. */
    virtual void spiRead(uint8_t* buf, size_t len) = 0;
    virtual void delayUs(uint32_t us) = 0;
    virtual void delayMs(uint32_t ms) = 0;
    /** @brief Monotonic millisecond counter (wraps; used with unsigned subtraction). */
    virtual uint32_t nowMs() = 0;

    /** @brief Assert CS# (no-op when the SPI driver owns CS). */
    void csLow()  { if (_cs != nullptr) _cs->set(false); }
    /** @brief Release CS# (no-op when the SPI driver owns CS). */
    void csHigh() { if (_cs != nullptr) _cs->set(true); }

    /** @brief Mark the configuration unsupported; bus operations become no-ops. */
    void invalidate() { _valid = false; }

private:
    OutputPin*      _cs;
    OutputPin*      _dc;
    OutputPin*      _res;
    InputPin*       _busy;
    DisplayWireMode _wireMode;
    uint32_t        _resetPulseUs;
    uint32_t        _resetSettleMs;
    OutputPin*      _enPin;
    bool            _enActiveHigh;
    bool            _enabled = true;
    bool            _valid = true;
};
