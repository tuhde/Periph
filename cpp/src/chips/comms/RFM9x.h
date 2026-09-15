#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/Connection.h"

/** @brief RFM9x (RFM95/96/97/98W) LoRa transceiver — minimal interface.
 *
 * All four modules share identical pins, register maps, SPI protocol, and
 * LoRa modem logic. They differ only in supported frequency bands and the
 * maximum spreading factor for RFM97W.
 *
 * The driver is built around an internal _RFM9xBase that owns all register
 * logic. Four thin variant subclasses — RFM95Minimal, RFM96Minimal,
 * RFM97Minimal, RFM98Minimal — supply the variant-specific frequency
 * limits, maximum SF, and band flag. The Full stage adds configuration,
 * hardware reset, and DIO0 interrupt-driven receive.
 *
 * Default configuration (baked in at construction):
 *     - Carrier frequency: per-constructor argument
 *     - SF 7, BW 125 kHz, CR 4/5, explicit header, CRC on
 *     - Preamble length 8
 *     - TX power +17 dBm on PA_BOOST
 *     - FIFO split: TX base 0x80, RX base 0x00
 *     - AGC enabled (RegModemConfig3 |= 0x04)
 *     - LNA boost enabled (HF variants only)
 *
 * @param connection Configured SPI connection bound to the device (CS managed
 *                   by the connection itself).
 * @param frequency_hz Carrier frequency in Hz; must lie in the variant's range.
 */
class _RFM9xBase {
public:
    _RFM9xBase(Connection& connection, uint32_t frequency_hz);

    /** @brief Send a packet.
     *  @param data Pointer to payload bytes.
     *  @param len  Payload length; max 255 bytes.
     */
    void send(const uint8_t* data, size_t len);

    /** @brief Receive a single packet.
     *  @param buf     Output buffer; must be at least 255 bytes.
     *  @param len     Output: number of bytes written (0 on timeout).
     *  @param timeout_ms Receive timeout in milliseconds (default 2000).
     *  @return true if a packet was received; false on timeout.
     */
    bool receive(uint8_t* buf, size_t& len, uint32_t timeout_ms = 2000);

protected:
    // Full-stage-only methods. Kept protected here so RFM9xMinimal variants
    // (which inherit _RFM9xBase publicly) do not expose them; each RFM9xFull
    // variant re-exposes them as public via `using` declarations.

    /** @brief Set the carrier frequency.
     *  @param frequency_hz Carrier frequency in Hz; must lie in the variant's range.
     */
    void set_frequency(uint32_t frequency_hz);

    /** @brief Enter STDBY mode (crystal on, RF/PLL off, FIFO accessible). */
    void standby();

    /** @brief Enter SLEEP mode (lowest power; FIFO inaccessible). */
    void sleep();

    /** @brief Read RegVersion. Expect 0x12 (SX1276). */
    uint8_t version();

    /** @brief Configure LoRa modulation parameters.
     *  @param sf            Spreading factor 6–12 (variant-capped; RFM97W max 9).
     *  @param bandwidth_khz Signal bandwidth in kHz (one of 7.8, 10.4, 15.6,
     *                       20.8, 31.25, 41.7, 62.5, 125, 250, 500).
     *  @param coding_rate   Coding rate denominator 5–8 (4/5 … 4/8).
     *  @param crc           true to enable CRC on RX payloads (default).
     */
    void configure(uint8_t sf, float bandwidth_khz, uint8_t coding_rate, bool crc = true);

    /** @brief Set TX output power.
     *  @param power_dbm    Output power in dBm. −1 to +14 (RFO) or +2 to +20 (PA_BOOST).
     *  @param use_pa_boost true to use PA_BOOST pin (default), false for RFO.
     */
    void set_tx_power(int8_t power_dbm, bool use_pa_boost = true);

    /** @brief Enter continuous receive mode. */
    void receive_continuous();

    /** @brief Read one packet from the FIFO in continuous receive mode.
     *  @param buf Output buffer; must be at least 255 bytes.
     *  @param len Output: bytes written.
     *  @return true if a packet was available.
     */
    bool read_packet(uint8_t* buf, size_t& len);

    /** @brief Return to STDBY from continuous receive mode. */
    void stop_receive();

    /** @brief Current channel RSSI in dBm (readable in continuous RX). */
    float rssi();

    /** @brief RSSI of last received packet in dBm. */
    float last_packet_rssi();

    /** @brief SNR of last received packet in dB (signed, ×0.25 raw). */
    float last_packet_snr();

    // Internal register-level helpers.
    static constexpr uint8_t REG_FIFO            = 0x00;
    static constexpr uint8_t REG_OP_MODE         = 0x01;
    static constexpr uint8_t REG_FRF_MSB         = 0x06;
    static constexpr uint8_t REG_FRF_MID         = 0x07;
    static constexpr uint8_t REG_FRF_LSB         = 0x08;
    static constexpr uint8_t REG_PA_CONFIG       = 0x09;
    static constexpr uint8_t REG_OCP             = 0x0B;
    static constexpr uint8_t REG_LNA             = 0x0C;
    static constexpr uint8_t REG_FIFO_ADDR_PTR   = 0x0D;
    static constexpr uint8_t REG_FIFO_TX_BASE    = 0x0E;
    static constexpr uint8_t REG_FIFO_RX_BASE    = 0x0F;
    static constexpr uint8_t REG_FIFO_RX_CURRENT = 0x10;
    static constexpr uint8_t REG_IRQ_FLAGS       = 0x12;
    static constexpr uint8_t REG_RX_NB_BYTES     = 0x13;
    static constexpr uint8_t REG_PKT_SNR         = 0x19;
    static constexpr uint8_t REG_PKT_RSSI        = 0x1A;
    static constexpr uint8_t REG_RSSI            = 0x1B;
    static constexpr uint8_t REG_MODEM_CONFIG_1  = 0x1D;
    static constexpr uint8_t REG_MODEM_CONFIG_2  = 0x1E;
    static constexpr uint8_t REG_PREAMBLE_LSB    = 0x21;
    static constexpr uint8_t REG_PAYLOAD_LENGTH  = 0x22;
    static constexpr uint8_t REG_MODEM_CONFIG_3  = 0x26;
    static constexpr uint8_t REG_DETECTION_OPT   = 0x31;
    static constexpr uint8_t REG_DETECTION_THR   = 0x37;
    static constexpr uint8_t REG_DIO_MAPPING_1   = 0x40;
    static constexpr uint8_t REG_VERSION         = 0x42;
    static constexpr uint8_t REG_PA_DAC          = 0x4D;

    static constexpr uint8_t MODE_LONG_RANGE = 0x80;
    static constexpr uint8_t MODE_SLEEP      = 0x00;
    static constexpr uint8_t MODE_STANDBY    = 0x01;
    static constexpr uint8_t MODE_TX         = 0x03;
    static constexpr uint8_t MODE_RX_CONT    = 0x05;
    static constexpr uint8_t MODE_RX_SINGLE  = 0x06;

    static constexpr uint8_t IRQ_TX_DONE    = 0x08;
    static constexpr uint8_t IRQ_RX_DONE    = 0x40;
    static constexpr uint8_t IRQ_RX_TIMEOUT = 0x80;

    static constexpr uint8_t PA_BOOST          = 0x80;
    static constexpr uint8_t PA_DAC_HIGH_POWER = 0x87;
    static constexpr uint8_t PA_DAC_DEFAULT    = 0x84;
    static constexpr uint8_t OCP_240MA         = 0x3B;
    static constexpr uint8_t OCP_DEFAULT       = 0x2B;

    static constexpr uint8_t DIO0_RX_DONE = 0x00;
    static constexpr uint8_t DIO0_TX_DONE = 0x40;

    static constexpr uint32_t FXOSC             = 32000000;
    static constexpr uint8_t  EXPECTED_VERSION  = 0x12;

    Connection& _connection;
    uint32_t    _frequency_hz;

    void _write_reg(uint8_t reg, uint8_t value);
    uint8_t _read_reg(uint8_t reg);
    void _burst_write(uint8_t reg, const uint8_t* data, size_t len);
    void _burst_read(uint8_t reg, uint8_t* buf, size_t len);
    void _enter_lora_sleep();
    void _delay_ms(unsigned long ms);
    bool _read_payload(uint8_t* buf, size_t& len);
    uint8_t _band_flag() const { return _lf_band ? 0x08 : 0x00; }

    /** @brief Shared body of Full::reset(), run once here and invoked by every
     *  variant's reset() (POR wait fallback; pin-driven reset wired in examples).
     *  Re-runs the same register sequence as the constructor.
     */
    void _reset_registers();

    // Variant-supplied constants (set per subclass).
    uint32_t _freq_min_hz;
    uint32_t _freq_max_hz;
    uint8_t  _max_sf;
    bool     _lf_band;
};

/** @brief RFM95W minimal driver — 868/915 MHz HF band, max SF=12. */
class RFM95Minimal : public _RFM9xBase {
public:
    RFM95Minimal(Connection& connection, uint32_t frequency_hz)
        : _RFM9xBase(connection, frequency_hz) {
        _freq_min_hz = 862000000;
        _freq_max_hz = 1020000000;
        _max_sf      = 12;
        _lf_band     = false;
    }
};

/** @brief RFM96W minimal driver — 433/470 MHz LF band, max SF=12. */
class RFM96Minimal : public _RFM9xBase {
public:
    RFM96Minimal(Connection& connection, uint32_t frequency_hz)
        : _RFM9xBase(connection, frequency_hz) {
        _freq_min_hz = 410000000;
        _freq_max_hz = 525000000;
        _max_sf      = 12;
        _lf_band     = true;
    }
};

/** @brief RFM97W minimal driver — 868/915 MHz HF band, max SF=9. */
class RFM97Minimal : public _RFM9xBase {
public:
    RFM97Minimal(Connection& connection, uint32_t frequency_hz)
        : _RFM9xBase(connection, frequency_hz) {
        _freq_min_hz = 862000000;
        _freq_max_hz = 1020000000;
        _max_sf      = 9;
        _lf_band     = false;
    }
};

/** @brief RFM98W minimal driver — 433/470 MHz LF band, max SF=12. */
class RFM98Minimal : public _RFM9xBase {
public:
    RFM98Minimal(Connection& connection, uint32_t frequency_hz)
        : _RFM9xBase(connection, frequency_hz) {
        _freq_min_hz = 410000000;
        _freq_max_hz = 525000000;
        _max_sf      = 12;
        _lf_band     = true;
    }
};

/** @brief RFM95W full driver — extends RFM95Minimal.
 *
 * Adds hardware reset (requires a wired reset pin) and exposes the full
 * configuration API: configure(), set_frequency(), set_tx_power(),
 * receive_continuous(), rssi(), etc.
 */
class RFM95Full : public RFM95Minimal {
public:
    RFM95Full(Connection& connection, uint32_t frequency_hz)
        : RFM95Minimal(connection, frequency_hz) {}

    // Re-expose the Full-stage register-level API, kept protected on
    // _RFM9xBase so *Minimal variants don't publicly expose it.
    using _RFM9xBase::set_frequency;
    using _RFM9xBase::standby;
    using _RFM9xBase::sleep;
    using _RFM9xBase::version;
    using _RFM9xBase::configure;
    using _RFM9xBase::set_tx_power;
    using _RFM9xBase::receive_continuous;
    using _RFM9xBase::read_packet;
    using _RFM9xBase::stop_receive;
    using _RFM9xBase::rssi;
    using _RFM9xBase::last_packet_rssi;
    using _RFM9xBase::last_packet_snr;

    /** @brief Hardware reset via NRESET pin (active low; >100 µs pulse, then 5 ms wait). */
    void reset();
};

/** @brief RFM96W full driver — extends RFM96Minimal. */
class RFM96Full : public RFM96Minimal {
public:
    RFM96Full(Connection& connection, uint32_t frequency_hz)
        : RFM96Minimal(connection, frequency_hz) {}

    using _RFM9xBase::set_frequency;
    using _RFM9xBase::standby;
    using _RFM9xBase::sleep;
    using _RFM9xBase::version;
    using _RFM9xBase::configure;
    using _RFM9xBase::set_tx_power;
    using _RFM9xBase::receive_continuous;
    using _RFM9xBase::read_packet;
    using _RFM9xBase::stop_receive;
    using _RFM9xBase::rssi;
    using _RFM9xBase::last_packet_rssi;
    using _RFM9xBase::last_packet_snr;

    void reset();
};

/** @brief RFM97W full driver — extends RFM97Minimal. */
class RFM97Full : public RFM97Minimal {
public:
    RFM97Full(Connection& connection, uint32_t frequency_hz)
        : RFM97Minimal(connection, frequency_hz) {}

    using _RFM9xBase::set_frequency;
    using _RFM9xBase::standby;
    using _RFM9xBase::sleep;
    using _RFM9xBase::version;
    using _RFM9xBase::configure;
    using _RFM9xBase::set_tx_power;
    using _RFM9xBase::receive_continuous;
    using _RFM9xBase::read_packet;
    using _RFM9xBase::stop_receive;
    using _RFM9xBase::rssi;
    using _RFM9xBase::last_packet_rssi;
    using _RFM9xBase::last_packet_snr;

    void reset();
};

/** @brief RFM98W full driver — extends RFM98Minimal. */
class RFM98Full : public RFM98Minimal {
public:
    RFM98Full(Connection& connection, uint32_t frequency_hz)
        : RFM98Minimal(connection, frequency_hz) {}

    using _RFM9xBase::set_frequency;
    using _RFM9xBase::standby;
    using _RFM9xBase::sleep;
    using _RFM9xBase::version;
    using _RFM9xBase::configure;
    using _RFM9xBase::set_tx_power;
    using _RFM9xBase::receive_continuous;
    using _RFM9xBase::read_packet;
    using _RFM9xBase::stop_receive;
    using _RFM9xBase::rssi;
    using _RFM9xBase::last_packet_rssi;
    using _RFM9xBase::last_packet_snr;

    void reset();
};
