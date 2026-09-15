#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/Connection.h"

/** @brief A CAN 2.0B data or remote frame.
 *
 * Fields:
 *   - id       — 11-bit (standard) or 29-bit (extended) identifier
 *   - data     — payload bytes, 0–8 bytes
 *   - extended — true for 29-bit extended-ID frames
 *   - rtr      — true for remote transmission request frames
 */
struct CanFrame {
    uint32_t id;
    const uint8_t* data;
    uint8_t  dlc;
    bool     extended;
    bool     rtr;
};

/** @brief MCP2515 stand-alone CAN 2.0B controller — base class.
 *
 * Owns the SPI connection and exposes private register-level helpers
 * (RESET, READ, WRITE, RTS, READ STATUS, RX STATUS, BIT MODIFY, LOAD
 * TX BUFFER, READ RX BUFFER). MCP2515Minimal adds the primary send/recv
 * API; MCP2515Full adds mode control, error counters, acceptance
 * filters/masks, abort and one-shot support, and explicit per-buffer
 * TX selection.
 *
 * Default configuration baked into Minimal:
 *   - Standard or extended ID accepted (set per-call)
 *   - TXB0/1/2 priority 3; TXB0 used by default
 *   - Accept-all filters (RXM[1:0]=11 in RXB0CTRL and RXB1CTRL)
 *   - BUKT=1 in RXB0CTRL (RXB0 → RXB1 rollover)
 *   - CANINTE=0x00 (polled operation)
 *   - OSM=0 (retransmit on error or loss of arbitration)
 *   - Bit timing: 125 kbit/s with 8 MHz oscillator
 */
class _MCP2515Base {
public:
    _MCP2515Base(Connection& connection, uint16_t bitrate_kbps = 125, uint8_t osc_mhz = 8);

    // CANSTAT.OPMOD[2:0] values — public because set_mode()/get_mode() take
    // and return these.
    static constexpr uint8_t CANSTAT_OPMOD_NORMAL      = 0x00;
    static constexpr uint8_t CANSTAT_OPMOD_SLEEP       = 0x20;
    static constexpr uint8_t CANSTAT_OPMOD_LOOPBACK    = 0x40;
    static constexpr uint8_t CANSTAT_OPMOD_LISTEN_ONLY = 0x60;
    static constexpr uint8_t CANSTAT_OPMOD_CONFIG      = 0x80;
    static constexpr uint8_t CANSTAT_OPMOD_MASK        = 0xE0;

protected:
    /** @brief Send a CAN frame on the next free TX buffer.
     *  @param id          11-bit (standard) or 29-bit (extended) identifier.
     *  @param data        Pointer to payload bytes (may be nullptr if dlc == 0).
     *  @param len         Payload length, 0–8.
     *  @param extended    true for 29-bit extended-ID frame.
     *  @param rtr         true for remote transmission request.
     *  @param buf_index   TX buffer 0/1/2, or 0xFF to auto-select.
     *  @return The TX buffer index used, or 0xFF if no buffer was free.
     */
    uint8_t _send(uint32_t id, const uint8_t* data, uint8_t len,
                 bool extended, bool rtr, uint8_t buf_index);

    /** @brief Poll READ STATUS until a frame is available, then read it.
     *  @param[out] frame  Populated with the received frame (data points
     *                     to a static 8-byte buffer owned by the driver;
     *                     copy out if you need to keep it).
     *  @param timeout_ms  0 = non-blocking poll; otherwise block up to N ms.
     *  @return true if a frame was returned; false on timeout.
     */
    bool _poll_rx(CanFrame& frame, uint32_t timeout_ms);

    /** @brief Switch operating mode and wait for CANSTAT to confirm. */
    void _set_mode(uint8_t mode);

    /** @brief Read CANSTAT.OPMOD and return it as one of the
     *         CANSTAT_OPMOD_* constants.
     */
    uint8_t _get_mode() const;

    /** @brief Configure an acceptance filter (must be in Configuration mode). */
    void _set_filter(uint8_t filter_num, uint32_t id, bool extended);

    /** @brief Configure an acceptance mask (must be in Configuration mode). */
    void _set_mask(uint8_t mask_num, uint32_t mask, bool extended);

    /** @brief Set RXM[1:0] for the given RX buffer. */
    void _set_rx_mode(uint8_t buf, uint8_t mode);

    /** @brief Issue SPI RESET (does NOT re-run init). */
    void _reset();

    /** @brief Set ABAT in CANCTRL; poll until cleared. */
    void _abort_tx();

    /** @brief Set OSM in CANCTRL. */
    void _set_one_shot(bool enable);

    /** @brief Clear RX0OVR or RX1OVR in EFLG. */
    void _clear_overflow(uint8_t buf);

    /** @brief Pack a CAN ID into SIDH/SIDL/EID8/EID0. */
    static void _pack_id(uint32_t id, bool extended,
                         uint8_t& sidh, uint8_t& sidl,
                         uint8_t& eid8, uint8_t& eid0);

    /** @brief Unpack a CAN ID from SIDH/SIDL/EID8/EID0, given the IDE bit. */
    static uint32_t _unpack_id(uint8_t sidh, uint8_t sidl,
                               uint8_t eid8, uint8_t eid0, bool ide);

    Connection& _connection;
    uint16_t    _bitrate_kbps;
    uint8_t     _osc_mhz;

    /** Static backing storage for received frame payload. */
    static uint8_t _rx_data_buf[8];

    // SPI instruction set
    static constexpr uint8_t INSTR_RESET       = 0xC0;
    static constexpr uint8_t INSTR_READ        = 0x03;
    static constexpr uint8_t INSTR_READ_RX_BUF = 0x90;
    static constexpr uint8_t INSTR_WRITE       = 0x02;
    static constexpr uint8_t INSTR_LOAD_TX_BUF = 0x40;
    static constexpr uint8_t INSTR_RTS         = 0x80;
    static constexpr uint8_t INSTR_READ_STATUS = 0xA0;
    static constexpr uint8_t INSTR_RX_STATUS   = 0xB0;
    static constexpr uint8_t INSTR_BIT_MODIFY  = 0x05;

    // Register addresses
    static constexpr uint8_t REG_CANSTAT  = 0x0E;
    static constexpr uint8_t REG_CANCTRL  = 0x0F;
    static constexpr uint8_t REG_CNF3     = 0x28;
    static constexpr uint8_t REG_CNF2     = 0x29;
    static constexpr uint8_t REG_CNF1     = 0x2A;
    static constexpr uint8_t REG_CANINTE  = 0x2B;
    static constexpr uint8_t REG_CANINTF  = 0x2C;
    static constexpr uint8_t REG_EFLG     = 0x2D;
    static constexpr uint8_t REG_TEC      = 0x1C;
    static constexpr uint8_t REG_REC      = 0x1D;
    static constexpr uint8_t REG_RXB0CTRL = 0x60;
    static constexpr uint8_t REG_RXB1CTRL = 0x70;
    static constexpr uint8_t REG_TXB0CTRL = 0x30;
    static constexpr uint8_t REG_TXB1CTRL = 0x40;
    static constexpr uint8_t REG_TXB2CTRL = 0x50;
    static constexpr uint8_t REG_RXM0SIDH = 0x20;
    static constexpr uint8_t REG_RXM1SIDH = 0x24;

    static constexpr uint8_t RXB0CTRL_RXM_MASK = 0x60;
    static constexpr uint8_t RXB0CTRL_RXM_ANY  = 0x60;
    static constexpr uint8_t RXB0CTRL_BUKT     = 0x04;

    static constexpr uint8_t TXBnCTRL_TXREQ   = 0x08;
    static constexpr uint8_t TXBnCTRL_TXP_MASK = 0x03;

    static constexpr uint8_t CANINTF_RX0IF = 0x01;
    static constexpr uint8_t CANINTF_RX1IF = 0x02;

    static constexpr uint8_t EFLG_RX0OVR = 0x40;
    static constexpr uint8_t EFLG_RX1OVR = 0x80;

    /** @brief Set the CNF1/CNF2/CNF3 register triplet for a given bitrate and oscillator.
     *  @param[out] cnf1  Value written to CNF1.
     *  @param[out] cnf2  Value written to CNF2.
     *  @param[out] cnf3  Value written to CNF3.
     */
    static void _cnf_for(uint16_t bitrate_kbps, uint8_t osc_mhz,
                         uint8_t& cnf1, uint8_t& cnf2, uint8_t& cnf3);

    void _write_reg(uint8_t reg, uint8_t value);
    uint8_t _read_reg(uint8_t reg) const;
    void _modify_reg(uint8_t reg, uint8_t mask, uint8_t value);
    uint8_t _read_status();
    void _rts(uint8_t mask);

    /** @brief Wait until CANSTAT.OPMOD matches the requested mode. */
    void _wait_op_mode(uint8_t target, uint32_t timeout_ms = 100);

    static void _delay_ms(unsigned long ms);
};

/** @brief MCP2515 minimal driver — send/recv with default configuration. */
class MCP2515Minimal : public _MCP2515Base {
public:
    /** @brief Construct and initialise the MCP2515.
     *  @param connection   SPI connection bound to the device.
     *  @param bitrate_kbps Bus bitrate in kbit/s (125, 250, 500, or 1000). Default 125.
     *  @param osc_mhz      Oscillator frequency in MHz (8 or 16). Default 8.
     */
    MCP2515Minimal(Connection& connection, uint16_t bitrate_kbps = 125, uint8_t osc_mhz = 8)
        : _MCP2515Base(connection, bitrate_kbps, osc_mhz) {}

    /** @brief Re-run the full init sequence with new bitrate/oscillator values. */
    void init(uint16_t bitrate_kbps = 125, uint8_t osc_mhz = 8);

    /** @brief Send a CAN frame.
     *  @param id        11-bit (standard) or 29-bit (extended) identifier.
     *  @param data      Pointer to payload bytes; may be nullptr if dlc == 0.
     *  @param len       Payload length, 0–8.
     *  @param extended  true for 29-bit extended-ID frame. Default false.
     *  @return The TX buffer index used, or 0xFF if no buffer was free.
     */
    uint8_t send(uint32_t id, const uint8_t* data, uint8_t len, bool extended = false);

    /** @brief Poll READ STATUS for a received frame.
     *  @param[out] frame  Populated with the received frame.
     *  @param timeout_ms  0 = non-blocking poll; otherwise block up to N ms.
     *  @return true if a frame was returned; false on timeout.
     */
    bool recv(CanFrame& frame, uint32_t timeout_ms = 0);
};

/** @brief MCP2515 full driver — adds mode/filter/error-counter methods. */
class MCP2515Full : public MCP2515Minimal {
public:
    MCP2515Full(Connection& connection, uint16_t bitrate_kbps = 125, uint8_t osc_mhz = 8)
        : MCP2515Minimal(connection, bitrate_kbps, osc_mhz) {}

    /** @brief Send a CAN frame on a specific TX buffer (0/1/2). */
    uint8_t send_buffered(uint32_t id, const uint8_t* data, uint8_t len,
                          bool extended = false, uint8_t buf = 0);

    /** @brief Configure an acceptance filter. */
    void set_filter(uint8_t filter_num, uint32_t id, bool extended = false);

    /** @brief Configure an acceptance mask. */
    void set_mask(uint8_t mask_num, uint32_t mask, bool extended = false);

    /** @brief Set RXM[1:0] for the given RX buffer. */
    void set_rx_mode(uint8_t buf, uint8_t mode);

    /** @brief Switch operating mode (normal/loopback/listen_only/sleep/config). */
    void set_mode(uint8_t mode);

    /** @brief Read the current operating mode (one of the CANSTAT_OPMOD_* values). */
    uint8_t get_mode() const { return _get_mode(); }

    /** @brief Issue SPI RESET (does NOT re-run init). */
    void reset() { _reset(); }

    /** @brief Read TEC, REC, EFLG into the output parameters. */
    void read_errors(uint8_t& tec, uint8_t& rec, uint8_t& eflg) const;

    /** @brief Clear the RX0OVR or RX1OVR flag in EFLG. */
    void clear_overflow(uint8_t buf) { _clear_overflow(buf); }

    /** @brief Set ABAT in CANCTRL; poll until cleared. */
    void abort_tx() { _abort_tx(); }

    /** @brief Set OSM in CANCTRL. */
    void set_one_shot(bool enable) { _set_one_shot(enable); }
};
