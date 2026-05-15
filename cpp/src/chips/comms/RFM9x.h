#pragma once
#include <stdint.h>
#include "../../transport/Transport.h"

/** @brief RFM95W — 868 / 915 MHz HF band, max SF 12. */
class RFM95Minimal {
public:
    RFM95Minimal(Transport& transport, uint32_t frequency_hz);
    /** @brief Transmit a packet.
     *  @param data Bytes to transmit (max 255 bytes).
     *  @param len Length of data.
     */
    void send(const uint8_t* data, uint8_t len);
    /** @brief Receive a packet (single shot).
     *  @param timeout_ms Timeout in milliseconds.
     *  @param out_len Pointer to store received length.
     *  @return Pointer to received buffer, or nullptr on timeout.
     */
    uint8_t* receive(uint16_t timeout_ms, uint8_t* out_len);
    /** @brief Read silicon revision.
     *  @return RegVersion value (expect 0x12 for SX1276).
     */
    uint8_t version();
    /** @brief Enter STANDBY mode. */
    void standby();
    /** @brief Enter SLEEP mode. */
    void sleep();

    static constexpr uint32_t FREQ_MIN_HZ = 862000000;
    static constexpr uint32_t FREQ_MAX_HZ = 1020000000;
    static constexpr uint8_t  MAX_SF = 12;
    static constexpr bool    IS_LF_BAND = false;

protected:
    Transport& _transport;
    uint32_t   _frequency_hz;
    uint8_t    _sf = 7;
    bool       _crc = true;

    static constexpr uint8_t  REG_FIFO              = 0x00;
    static constexpr uint8_t  REG_OP_MODE           = 0x01;
    static constexpr uint8_t  REG_FRF_MSB           = 0x06;
    static constexpr uint8_t  REG_FRF_MID           = 0x07;
    static constexpr uint8_t  REG_FRF_LSB           = 0x08;
    static constexpr uint8_t  REG_PA_CONFIG         = 0x09;
    static constexpr uint8_t  REG_OCP               = 0x0B;
    static constexpr uint8_t  REG_LNA               = 0x0C;
    static constexpr uint8_t  REG_FIFO_ADDR_PTR     = 0x0D;
    static constexpr uint8_t  REG_FIFO_TX_BASE_ADDR = 0x0E;
    static constexpr uint8_t  REG_FIFO_RX_BASE_ADDR = 0x0F;
    static constexpr uint8_t  REG_FIFO_RX_CURRENT_ADDR = 0x10;
    static constexpr uint8_t  REG_IRQ_FLAGS         = 0x12;
    static constexpr uint8_t  REG_RX_NB_BYTES       = 0x13;
    static constexpr uint8_t  REG_PKT_SNR_VALUE     = 0x19;
    static constexpr uint8_t  REG_PKT_RSSI_VALUE    = 0x1A;
    static constexpr uint8_t  REG_RSSI_VALUE        = 0x1B;
    static constexpr uint8_t  REG_MODEM_CONFIG1     = 0x1D;
    static constexpr uint8_t  REG_MODEM_CONFIG2     = 0x1E;
    static constexpr uint8_t  REG_MODEM_CONFIG3     = 0x26;
    static constexpr uint8_t  REG_VERSION           = 0x42;
    static constexpr uint8_t  REG_PA_DAC            = 0x4D;

    static constexpr uint32_t FXOSC = 32000000;
    static constexpr uint8_t  MODE_SLEEP = 0;
    static constexpr uint8_t  MODE_STDBY = 1;
    static constexpr uint8_t  MODE_TX    = 3;
    static constexpr uint8_t  MODE_RXSINGLE = 6;
    static constexpr uint8_t  MODE_RXCONTINUOUS = 5;
    static constexpr uint8_t  IRQ_TX_DONE = 0x08;
    static constexpr uint8_t  IRQ_RX_DONE = 0x40;
    static constexpr uint8_t  IRQ_RX_TIMEOUT = 0x80;

    void _write_reg(uint8_t reg, uint8_t value);
    void _write_reg_burst(uint8_t reg, const uint8_t* data, uint8_t len);
    uint16_t _read_reg(uint8_t reg);
    void _set_mode(uint8_t mode);
    void _set_frequency(uint32_t frequency_hz);
    bool _poll_irq(uint8_t irq_mask, uint16_t timeout_ms);
    uint8_t _map_bw(uint8_t bandwidth_khz);
    uint8_t _map_cr(uint8_t coding_rate);
};

/** @brief RFM95W full interface — extends RFM95Minimal with configuration and GPIO support.
 *
 *  @param transport   Configured SPI transport.
 *  @param frequency_hz Carrier frequency in Hz.
 *  @param reset_pin   Optional GPIO pin for hardware reset.
 *  @param dio0_pin    Optional GPIO pin for DIO0 interrupt.
 */
class RFM95Full : public RFM95Minimal {
public:
    RFM95Full(Transport& transport, uint32_t frequency_hz,
              uint8_t reset_pin = 0, uint8_t dio0_pin = 0);

    /** @brief Hardware reset via NRESET pin. */
    void reset();
    /** @brief Configure modem parameters.
     *  @param sf Spreading factor 6–12 (capped at MAX_SF).
     *  @param bandwidth_khz Signal bandwidth in kHz.
     *  @param coding_rate Coding rate denominator 5–8.
     *  @param crc Enable CRC generation and verification.
     */
    void configure(uint8_t sf, uint8_t bandwidth_khz, uint8_t coding_rate, bool crc = true);
    /** @brief Change carrier frequency.
     *  @param frequency_hz New carrier frequency in Hz.
     */
    void set_frequency(uint32_t frequency_hz);
    /** @brief Set TX output power.
     *  @param power_dbm Output power in dBm.
     *  @param use_pa_boost Use PA_BOOST (max +20 dBm) if true, RFO (max +14 dBm) if false.
     */
    void set_tx_power(int8_t power_dbm, bool use_pa_boost = true);
    /** @brief Enter continuous receive mode. */
    void receive_continuous();
    /** @brief Read one packet from FIFO in continuous mode.
     *  @param out_len Pointer to store received length.
     *  @return Pointer to received buffer, or nullptr if no packet available.
     */
    uint8_t* read_packet(uint8_t* out_len);
    /** @brief Return to STANDBY from RXCONTINUOUS. */
    void stop_receive();
    /** @brief Read current channel RSSI.
     *  @return RSSI in dBm.
     */
    float rssi();
    /** @brief Read RSSI of last received packet.
     *  @return Packet RSSI in dBm.
     */
    float last_packet_rssi();
    /** @brief Read SNR of last received packet.
     *  @return Packet SNR in dB.
     */
    float last_packet_snr();

private:
    uint8_t  _reset_pin = 0;
    uint8_t  _dio0_pin = 0;
    uint8_t  _bw = 125;
    uint8_t  _cr = 5;

    void _delay_us(uint32_t us);
    void _digital_write(uint8_t pin, uint8_t value);
    void _pin_mode(uint8_t pin, uint8_t mode);
};

/** @brief RFM96W — 433 / 470 MHz LF band, max SF 12. */
class RFM96Minimal {
public:
    RFM96Minimal(Transport& transport, uint32_t frequency_hz);
    void send(const uint8_t* data, uint8_t len);
    uint8_t* receive(uint16_t timeout_ms, uint8_t* out_len);
    uint8_t version();
    void standby();
    void sleep();

    static constexpr uint32_t FREQ_MIN_HZ = 410000000;
    static constexpr uint32_t FREQ_MAX_HZ = 525000000;
    static constexpr uint8_t  MAX_SF = 12;
    static constexpr bool    IS_LF_BAND = true;

protected:
    Transport& _transport;
    uint32_t   _frequency_hz;
    uint8_t    _sf = 7;
    bool       _crc = true;

    static constexpr uint8_t  REG_FIFO              = 0x00;
    static constexpr uint8_t  REG_OP_MODE           = 0x01;
    static constexpr uint8_t  REG_FRF_MSB           = 0x06;
    static constexpr uint8_t  REG_FRF_MID           = 0x07;
    static constexpr uint8_t  REG_FRF_LSB           = 0x08;
    static constexpr uint8_t  REG_PA_CONFIG         = 0x09;
    static constexpr uint8_t  REG_OCP               = 0x0B;
    static constexpr uint8_t  REG_LNA               = 0x0C;
    static constexpr uint8_t  REG_FIFO_ADDR_PTR     = 0x0D;
    static constexpr uint8_t  REG_FIFO_TX_BASE_ADDR = 0x0E;
    static constexpr uint8_t  REG_FIFO_RX_BASE_ADDR = 0x0F;
    static constexpr uint8_t  REG_FIFO_RX_CURRENT_ADDR = 0x10;
    static constexpr uint8_t  REG_IRQ_FLAGS         = 0x12;
    static constexpr uint8_t  REG_RX_NB_BYTES       = 0x13;
    static constexpr uint8_t  REG_PKT_SNR_VALUE     = 0x19;
    static constexpr uint8_t  REG_PKT_RSSI_VALUE    = 0x1A;
    static constexpr uint8_t  REG_RSSI_VALUE        = 0x1B;
    static constexpr uint8_t  REG_MODEM_CONFIG1     = 0x1D;
    static constexpr uint8_t  REG_MODEM_CONFIG2     = 0x1E;
    static constexpr uint8_t  REG_MODEM_CONFIG3     = 0x26;
    static constexpr uint8_t  REG_VERSION           = 0x42;
    static constexpr uint8_t  REG_PA_DAC            = 0x4D;

    static constexpr uint32_t FXOSC = 32000000;
    static constexpr uint8_t  MODE_SLEEP = 0;
    static constexpr uint8_t  MODE_STDBY = 1;
    static constexpr uint8_t  MODE_TX    = 3;
    static constexpr uint8_t  MODE_RXSINGLE = 6;
    static constexpr uint8_t  MODE_RXCONTINUOUS = 5;
    static constexpr uint8_t  IRQ_TX_DONE = 0x08;
    static constexpr uint8_t  IRQ_RX_DONE = 0x40;
    static constexpr uint8_t  IRQ_RX_TIMEOUT = 0x80;

    void _write_reg(uint8_t reg, uint8_t value);
    void _write_reg_burst(uint8_t reg, const uint8_t* data, uint8_t len);
    uint16_t _read_reg(uint8_t reg);
    void _set_mode(uint8_t mode);
    void _set_frequency(uint32_t frequency_hz);
    bool _poll_irq(uint8_t irq_mask, uint16_t timeout_ms);
    uint8_t _map_bw(uint8_t bandwidth_khz);
    uint8_t _map_cr(uint8_t coding_rate);
};

/** @brief RFM96W full interface. */
class RFM96Full : public RFM96Minimal {
public:
    RFM96Full(Transport& transport, uint32_t frequency_hz,
              uint8_t reset_pin = 0, uint8_t dio0_pin = 0);

    void reset();
    void configure(uint8_t sf, uint8_t bandwidth_khz, uint8_t coding_rate, bool crc = true);
    void set_frequency(uint32_t frequency_hz);
    void set_tx_power(int8_t power_dbm, bool use_pa_boost = true);
    void receive_continuous();
    uint8_t* read_packet(uint8_t* out_len);
    void stop_receive();
    float rssi();
    float last_packet_rssi();
    float last_packet_snr();

private:
    uint8_t  _reset_pin = 0;
    uint8_t  _dio0_pin = 0;
    uint8_t  _bw = 125;
    uint8_t  _cr = 5;

    void _delay_us(uint32_t us);
    void _digital_write(uint8_t pin, uint8_t value);
    void _pin_mode(uint8_t pin, uint8_t mode);
};

/** @brief RFM97W — 868 / 915 MHz HF band, max SF 9. */
class RFM97Minimal {
public:
    RFM97Minimal(Transport& transport, uint32_t frequency_hz);
    void send(const uint8_t* data, uint8_t len);
    uint8_t* receive(uint16_t timeout_ms, uint8_t* out_len);
    uint8_t version();
    void standby();
    void sleep();

    static constexpr uint32_t FREQ_MIN_HZ = 862000000;
    static constexpr uint32_t FREQ_MAX_HZ = 1020000000;
    static constexpr uint8_t  MAX_SF = 9;
    static constexpr bool    IS_LF_BAND = false;

protected:
    Transport& _transport;
    uint32_t   _frequency_hz;
    uint8_t    _sf = 7;
    bool       _crc = true;

    static constexpr uint8_t  REG_FIFO              = 0x00;
    static constexpr uint8_t  REG_OP_MODE           = 0x01;
    static constexpr uint8_t  REG_FRF_MSB           = 0x06;
    static constexpr uint8_t  REG_FRF_MID           = 0x07;
    static constexpr uint8_t  REG_FRF_LSB           = 0x08;
    static constexpr uint8_t  REG_PA_CONFIG         = 0x09;
    static constexpr uint8_t  REG_OCP               = 0x0B;
    static constexpr uint8_t  REG_LNA               = 0x0C;
    static constexpr uint8_t  REG_FIFO_ADDR_PTR     = 0x0D;
    static constexpr uint8_t  REG_FIFO_TX_BASE_ADDR = 0x0E;
    static constexpr uint8_t  REG_FIFO_RX_BASE_ADDR = 0x0F;
    static constexpr uint8_t  REG_FIFO_RX_CURRENT_ADDR = 0x10;
    static constexpr uint8_t  REG_IRQ_FLAGS         = 0x12;
    static constexpr uint8_t  REG_RX_NB_BYTES       = 0x13;
    static constexpr uint8_t  REG_PKT_SNR_VALUE     = 0x19;
    static constexpr uint8_t  REG_PKT_RSSI_VALUE    = 0x1A;
    static constexpr uint8_t  REG_RSSI_VALUE        = 0x1B;
    static constexpr uint8_t  REG_MODEM_CONFIG1     = 0x1D;
    static constexpr uint8_t  REG_MODEM_CONFIG2     = 0x1E;
    static constexpr uint8_t  REG_MODEM_CONFIG3     = 0x26;
    static constexpr uint8_t  REG_VERSION           = 0x42;
    static constexpr uint8_t  REG_PA_DAC            = 0x4D;

    static constexpr uint32_t FXOSC = 32000000;
    static constexpr uint8_t  MODE_SLEEP = 0;
    static constexpr uint8_t  MODE_STDBY = 1;
    static constexpr uint8_t  MODE_TX    = 3;
    static constexpr uint8_t  MODE_RXSINGLE = 6;
    static constexpr uint8_t  MODE_RXCONTINUOUS = 5;
    static constexpr uint8_t  IRQ_TX_DONE = 0x08;
    static constexpr uint8_t  IRQ_RX_DONE = 0x40;
    static constexpr uint8_t  IRQ_RX_TIMEOUT = 0x80;

    void _write_reg(uint8_t reg, uint8_t value);
    void _write_reg_burst(uint8_t reg, const uint8_t* data, uint8_t len);
    uint16_t _read_reg(uint8_t reg);
    void _set_mode(uint8_t mode);
    void _set_frequency(uint32_t frequency_hz);
    bool _poll_irq(uint8_t irq_mask, uint16_t timeout_ms);
    uint8_t _map_bw(uint8_t bandwidth_khz);
    uint8_t _map_cr(uint8_t coding_rate);
};

/** @brief RFM97W full interface. */
class RFM97Full : public RFM97Minimal {
public:
    RFM97Full(Transport& transport, uint32_t frequency_hz,
              uint8_t reset_pin = 0, uint8_t dio0_pin = 0);

    void reset();
    void configure(uint8_t sf, uint8_t bandwidth_khz, uint8_t coding_rate, bool crc = true);
    void set_frequency(uint32_t frequency_hz);
    void set_tx_power(int8_t power_dbm, bool use_pa_boost = true);
    void receive_continuous();
    uint8_t* read_packet(uint8_t* out_len);
    void stop_receive();
    float rssi();
    float last_packet_rssi();
    float last_packet_snr();

private:
    uint8_t  _reset_pin = 0;
    uint8_t  _dio0_pin = 0;
    uint8_t  _bw = 125;
    uint8_t  _cr = 5;

    void _delay_us(uint32_t us);
    void _digital_write(uint8_t pin, uint8_t value);
    void _pin_mode(uint8_t pin, uint8_t mode);
};

/** @brief RFM98W — 433 / 470 MHz LF band, max SF 12. */
class RFM98Minimal {
public:
    RFM98Minimal(Transport& transport, uint32_t frequency_hz);
    void send(const uint8_t* data, uint8_t len);
    uint8_t* receive(uint16_t timeout_ms, uint8_t* out_len);
    uint8_t version();
    void standby();
    void sleep();

    static constexpr uint32_t FREQ_MIN_HZ = 410000000;
    static constexpr uint32_t FREQ_MAX_HZ = 525000000;
    static constexpr uint8_t  MAX_SF = 12;
    static constexpr bool    IS_LF_BAND = true;

protected:
    Transport& _transport;
    uint32_t   _frequency_hz;
    uint8_t    _sf = 7;
    bool       _crc = true;

    static constexpr uint8_t  REG_FIFO              = 0x00;
    static constexpr uint8_t  REG_OP_MODE           = 0x01;
    static constexpr uint8_t  REG_FRF_MSB           = 0x06;
    static constexpr uint8_t  REG_FRF_MID           = 0x07;
    static constexpr uint8_t  REG_FRF_LSB           = 0x08;
    static constexpr uint8_t  REG_PA_CONFIG         = 0x09;
    static constexpr uint8_t  REG_OCP               = 0x0B;
    static constexpr uint8_t  REG_LNA               = 0x0C;
    static constexpr uint8_t  REG_FIFO_ADDR_PTR     = 0x0D;
    static constexpr uint8_t  REG_FIFO_TX_BASE_ADDR = 0x0E;
    static constexpr uint8_t  REG_FIFO_RX_BASE_ADDR = 0x0F;
    static constexpr uint8_t  REG_FIFO_RX_CURRENT_ADDR = 0x10;
    static constexpr uint8_t  REG_IRQ_FLAGS         = 0x12;
    static constexpr uint8_t  REG_RX_NB_BYTES       = 0x13;
    static constexpr uint8_t  REG_PKT_SNR_VALUE     = 0x19;
    static constexpr uint8_t  REG_PKT_RSSI_VALUE    = 0x1A;
    static constexpr uint8_t  REG_RSSI_VALUE        = 0x1B;
    static constexpr uint8_t  REG_MODEM_CONFIG1     = 0x1D;
    static constexpr uint8_t  REG_MODEM_CONFIG2     = 0x1E;
    static constexpr uint8_t  REG_MODEM_CONFIG3     = 0x26;
    static constexpr uint8_t  REG_VERSION           = 0x42;
    static constexpr uint8_t  REG_PA_DAC            = 0x4D;

    static constexpr uint32_t FXOSC = 32000000;
    static constexpr uint8_t  MODE_SLEEP = 0;
    static constexpr uint8_t  MODE_STDBY = 1;
    static constexpr uint8_t  MODE_TX    = 3;
    static constexpr uint8_t  MODE_RXSINGLE = 6;
    static constexpr uint8_t  MODE_RXCONTINUOUS = 5;
    static constexpr uint8_t  IRQ_TX_DONE = 0x08;
    static constexpr uint8_t  IRQ_RX_DONE = 0x40;
    static constexpr uint8_t  IRQ_RX_TIMEOUT = 0x80;

    void _write_reg(uint8_t reg, uint8_t value);
    void _write_reg_burst(uint8_t reg, const uint8_t* data, uint8_t len);
    uint16_t _read_reg(uint8_t reg);
    void _set_mode(uint8_t mode);
    void _set_frequency(uint32_t frequency_hz);
    bool _poll_irq(uint8_t irq_mask, uint16_t timeout_ms);
    uint8_t _map_bw(uint8_t bandwidth_khz);
    uint8_t _map_cr(uint8_t coding_rate);
};

/** @brief RFM98W full interface. */
class RFM98Full : public RFM98Minimal {
public:
    RFM98Full(Transport& transport, uint32_t frequency_hz,
              uint8_t reset_pin = 0, uint8_t dio0_pin = 0);

    void reset();
    void configure(uint8_t sf, uint8_t bandwidth_khz, uint8_t coding_rate, bool crc = true);
    void set_frequency(uint32_t frequency_hz);
    void set_tx_power(int8_t power_dbm, bool use_pa_boost = true);
    void receive_continuous();
    uint8_t* read_packet(uint8_t* out_len);
    void stop_receive();
    float rssi();
    float last_packet_rssi();
    float last_packet_snr();

private:
    uint8_t  _reset_pin = 0;
    uint8_t  _dio0_pin = 0;
    uint8_t  _bw = 125;
    uint8_t  _cr = 5;

    void _delay_us(uint32_t us);
    void _digital_write(uint8_t pin, uint8_t value);
    void _pin_mode(uint8_t pin, uint8_t mode);
};