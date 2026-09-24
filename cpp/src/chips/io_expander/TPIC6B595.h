#pragma once
#include <stdint.h>
#include <stddef.h>

#ifndef OUTPUT
#define INPUT        0
#define OUTPUT       1
#define INPUT_PULLUP 2
#define HIGH         1
#define LOW          0
#endif

/**
 * @brief TPIC6B595 8-bit power SIPO shift register — minimal interface.
 *
 * Drives up to @c num_devices cascaded TPIC6B595s through a SiPo (serial-
 * in/parallel-out) connection. Each device exposes 8 open-drain outputs
 * (DRAIN0–DRAIN7); every write shifts the entire cascade MSB-first and pulses
 * RCK to latch all outputs atomically. Outputs only sink current — they never
 * source it; an external pull-up or load supply is required for the "off"/
 * high state.
 *
 * The driver owns a @c num_devices -byte shadow register so single-pin updates
 * work without re-reading the bus. Every write (pin, port, fill, write_all)
 * rebuilds and retransmits the entire reversed cascade — see
 * @c specs/io_expander/tpic6b595.md for the wire-order reversal that cascading
 * requires.
 *
 * Initialises every output to OFF at construction (shadow zero, latched
 * once). If the SiPo connection has SRCLR wired, the constructor pulses
 * it to clear the shift register before the all-zero latch.
 *
 * Generic over any SiPo connection class that exposes
 * @c write(const uint8_t*, size_t), @c clear(), and @c set_output_enable(bool)
 * — covers @c SiPoConnection (Arduino), @c SiPoConnectionLinux,
 * @c SiPoConnectionZephyr, @c SiPoConnectionESPIDF, @c SiPoConnectionPicoSDK.
 *
 * @tparam CONN  SiPo connection class (must have write / clear / set_output_enable).
 *
 * @param connection Configured SiPo connection.
 * @param num_devices Number of cascaded TPIC6B595s on the wire; default 1.
 */
template <typename CONN>
class TPIC6B595Minimal {
public:
    /**
     * @brief GPIO proxy for a single TPIC6B595 pin — output-only.
     *
     * Obtain via @c TPIC6B595Minimal::pin(). Do not construct directly.
     * Reuses Arduino GPIO constants: HIGH, LOW.
     */
    class IOExpanderPin {
    public:
        /**
         * @brief Construct a pin proxy.
         * @param chip Parent TPIC6B595Minimal instance.
         * @param n    Pin index 0..(num_devices * 8 - 1).
         */
        IOExpanderPin(TPIC6B595Minimal& chip, uint16_t n) : _chip(chip), _n(n) {}

        /** @brief Write pin output latch.
         *  @param v HIGH (DMOS ON — sinks current through the external load)
         *           or LOW (DMOS OFF — high-impedance). */
        void write(uint8_t v) { _chip._set_pin(_n, v ? 1 : 0); }

        /** @brief Read pin shadow bit (NOT a bus read — SiPo is write-only).
         *  @return HIGH or LOW. */
        uint8_t read() const {
            uint8_t port = _n / 8;
            uint8_t bit  = _n % 8;
            return (_chip._shadow[port] >> bit) & 1;
        }

        /** @brief Set DMOS output ON (sink current). */
        void high()   { write(HIGH); }
        /** @brief Set DMOS output OFF (high-impedance). */
        void low()    { write(LOW);  }
        /** @brief Invert the shadow bit for this pin. */
        void toggle() { write(read() ^ 1); }

        /** @brief OutputPin interface — satisfies the project's @c OutputPin contract.
         *  @param high true = ON, false = OFF. */
        void set(bool high) { write(high ? HIGH : LOW); }

    protected:
        TPIC6B595Minimal& _chip;
        uint16_t          _n;
    };

    /** @brief Construct and initialise the TPIC6B595.
     *
     *  Zeroes every shadow byte, pulses SRCLR if the SiPo connection has it
     *  wired (no-op otherwise — the constructor ignores the result of
     *  @c clear() so missing SRCLR on the connection is not fatal), then
     *  writes the all-zero reversed cascade so every output starts OFF
     *  regardless of the chip's undefined power-on storage-register content.
     *
     *  @param connection Configured SiPo connection.
     *  @param num_devices Number of cascaded TPIC6B595s on the wire; default 1.
     */
    TPIC6B595Minimal(CONN& connection, uint8_t num_devices = 1)
        : _connection(connection), _num_devices(num_devices)
    {
        for (uint8_t i = 0; i < num_devices; ++i) _shadow[i] = 0;
        (void)_connection.clear();
        _flush();
    }

    /** @brief Return a pin proxy for global pin n (0..num_devices * 8 - 1).
     *  @param n Pin index (0 = DRAIN0 of the device nearest the controller).
     *  @return IOExpanderPin proxy. */
    IOExpanderPin pin(uint16_t n) { return IOExpanderPin(*this, n); }

    /** @brief Write all 8 outputs of cascaded device @p port from @p mask.
     *
     *  Updates the shadow register, rebuilds the reversed cascade, shifts
     *  it out, and pulses RCK to latch every cascaded device's outputs.
     *
     *  @param port Cascaded device index (0 = nearest the controller).
     *  @param mask 8-bit output mask. Bit 0 = DRAIN0, bit 7 = DRAIN7.
     *              1 = ON (DMOS conducting, sinks current); 0 = OFF
     *              (high-impedance). */
    void write_port(uint8_t port, uint8_t mask) {
        _shadow[port] = mask;
        _flush();
    }

    /** @brief Set every pin on every cascaded device to @p value.
     *
     *  Sends immediately — the fast path for "all on"/"all off".
     *
     *  @param value true to turn every output ON, false to turn them OFF. */
    void fill(bool value) {
        uint8_t b = value ? 0xFF : 0x00;
        for (uint8_t i = 0; i < _num_devices; ++i) _shadow[i] = b;
        _flush();
    }

    /** @brief Turn every output off (equivalent to @c fill(false)). */
    void off() { fill(false); }

    /** @brief Maximum cascade depth supported by the shadow buffer. */
    static constexpr uint8_t MAX_DEVICES = 8;

    uint8_t _shadow[MAX_DEVICES];

protected:
    CONN&   _connection;
    uint8_t _num_devices;

    void _flush() {
        uint8_t wire[MAX_DEVICES];
        for (uint8_t i = 0; i < _num_devices; ++i) {
            wire[i] = _shadow[_num_devices - 1 - i];
        }
        _connection.write(wire, _num_devices);
    }

    void _set_pin(uint16_t n, uint8_t value) {
        uint8_t port = n / 8;
        uint8_t bit  = n % 8;
        if (value) _shadow[port] |= (uint8_t)(1u << bit);
        else       _shadow[port] &= (uint8_t)~(1u << bit);
        _flush();
    }
};

/**
 * @brief TPIC6B595 full interface — extends TPIC6B595Minimal with hardware features.
 *
 * Adds @c clear(), @c set_output_enable(), and @c write_all() for the
 * shift-register-clear and output-enable hardware lines, plus a bulk
 * multi-device write. The Pin API surface stays exactly Minimal's — every
 * TPIC6B595 pin is a fixed, capability-less output.
 *
 * @tparam CONN  SiPo connection class.
 *
 * @param connection Configured SiPo connection.
 * @param num_devices Number of cascaded TPIC6B595s on the wire; default 1.
 */
template <typename CONN>
class TPIC6B595Full : public TPIC6B595Minimal<CONN> {
public:
    /** @brief Construct and initialise the TPIC6B595Full. */
    TPIC6B595Full(CONN& connection, uint8_t num_devices = 1)
        : TPIC6B595Minimal<CONN>(connection, num_devices) {}

    /** @brief Pulse SRCLR to clear the shift register only.
     *
     *  The storage register (and therefore the DRAIN outputs) keeps its
     *  last-latched value until the next RCK pulse. To actually blank the
     *  outputs, call @c off() or @c fill(false) afterwards.
     *
     *  @return Whatever the underlying connection's @c clear() returns —
     *          typically 0 on success, a negative error code / @c false
     *          if SRCLR was not wired. */
    auto clear() -> decltype(this->_connection.clear()) { return this->_connection.clear(); }

    /** @brief Drive G LOW (@p enabled=true) or HIGH (@p enabled=false).
     *
     *  @p enabled=true lets the storage register drive the outputs.
     *  @p enabled=false forces every output off without disturbing the
     *  shadow register or the storage register's contents — the datasheet's
     *  documented use case for global PWM-style brightness dimming.
     *
     *  @return Whatever the underlying connection's @c set_output_enable()
     *          returns. */
    auto set_output_enable(bool enabled) -> decltype(this->_connection.set_output_enable(enabled)) { return this->_connection.set_output_enable(enabled); }

    /** @brief Write every cascaded device's byte in one call.
     *
     *  Updates the whole shadow register and performs exactly one transmit
     *  + latch. @p values is length-truncated or zero-extended to
     *  @c num_devices as needed.
     *
     *  @param values Byte array (one entry per cascaded device).
     *  @param len    Number of entries in @p values. */
    void write_all(const uint8_t* values, size_t len) {
        uint8_t n = this->_num_devices;
        for (uint8_t i = 0; i < n; ++i) {
            uint8_t v = (i < len) ? values[i] : 0;
            this->_shadow[i] = v;
        }
        this->_flush();
    }
};
