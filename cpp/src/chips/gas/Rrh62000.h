#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/RegisterConnection.h"

/** @brief Main air-quality values of one RRH62000 sample (RRH62000Minimal::read()). */
struct RRH62000Measurement {
    float pm1_ug_m3;      ///< PM1 mass concentration, KCl reference, µg/m³
    float pm2_5_ug_m3;    ///< PM2.5 mass concentration, KCl reference, µg/m³
    float pm10_ug_m3;     ///< PM10 mass concentration, KCl reference, µg/m³
    float temperature_c;  ///< temperature, °C
    float humidity_rh;    ///< relative humidity, %RH
    uint32_t tvoc;        ///< total volatile organic compounds, µg/m³ (LSB 10 µg/m³)
    uint16_t eco2_ppm;    ///< estimated CO₂, ppm (400–5000)
    float iaq;            ///< UBA indoor air quality index, 1.00–5.00
};

/** @brief The decoded status word of the measurement block. */
struct RRH62000Status {
    uint16_t raw;             ///< status word (byte 0 | byte 1 << 8)
    bool high_concentration;  ///< bit 0: dust concentration extremely high (not measurable)
    bool dust_accumulation;   ///< bit 1: dust accumulated inside the sensor (not measurable)
    bool fan_speed;           ///< bit 2: fan speed out of the set range
    bool fan;                 ///< bit 3: fan malfunction or breakdown
};

/** @brief Particle values of the measurement block (RRH62000Full::read_particles()). */
struct RRH62000Particles {
    float pm1_ug_m3;          ///< PM1, KCl reference, µg/m³
    float pm2_5_ug_m3;        ///< PM2.5, KCl reference, µg/m³
    float pm10_ug_m3;         ///< PM10, KCl reference, µg/m³
    float pm1_smoke_ug_m3;    ///< PM1, cigarette-smoke reference, µg/m³
    float pm2_5_smoke_ug_m3;  ///< PM2.5, cigarette-smoke reference, µg/m³
    float pm10_smoke_ug_m3;   ///< PM10, cigarette-smoke reference, µg/m³
    float nc_0_3;             ///< particles 0.3–10 µm per cm³
    float nc_0_5;             ///< particles 0.5–10 µm per cm³
    float nc_1;               ///< particles 1–10 µm per cm³
    float nc_2_5;             ///< particles 2.5–10 µm per cm³
    float nc_4;               ///< particles 4–10 µm per cm³
};

/** @brief The complete decoded measurement block (RRH62000Full::read_all()). */
struct RRH62000Data {
    RRH62000Status status;        ///< status word and flags
    RRH62000Particles particles;  ///< PM values (both references) and number concentrations
    float temperature_c;          ///< temperature, °C
    float humidity_rh;            ///< relative humidity, %RH
    uint32_t tvoc;                ///< TVOC, µg/m³
    uint16_t eco2_ppm;            ///< estimated CO₂, ppm
    float iaq;                    ///< UBA IAQ index, 1.00–5.00
};

/** @brief RRH62000 all-in-one air-quality module (Renesas) — minimal interface.
 *
 *  One call returns PM1/PM2.5/PM10 (KCl reference), temperature, humidity, TVOC, estimated CO₂
 *  and the UBA IAQ index. The module is a register-addressed I²C device at 0x69 (SEL pin to
 *  GND): a one-byte command code is the register pointer; reading 0x00 returns the 37-byte
 *  measurement block with a CRC-8.
 *
 *  The module must not be accessed more often than once per second (tRD); every method waits
 *  for the access interval and therefore may block for about a second per bus access. The
 *  start-up time (1 s for I²C, 3.3 s for valid data) is counted from construction, wake() and
 *  soft_reset(). The module defaults (10 moving averages, fan 86 %, cleaning every 7 days for
 *  10 s) are kept.
 *
 *  Errors: methods return false (or a negative value) and last_error() gives the reason (one
 *  of the ERR_* values).
 *
 *  @param connection RegisterConnection (1-byte register addresses) bound to address 0x69.
 *  @param accessIntervalMs Minimum spacing between two accesses (the module needs ≥ 1000 ms;
 *  lower it only for mocks). The constructor does no bus access.
 */
class RRH62000Minimal {
public:
    static constexpr int ERR_BUS = -1;      ///< I²C transfer failed
    static constexpr int ERR_CRC = -2;      ///< CRC-8 of the block wrong
    static constexpr int ERR_INVALID = -3;  ///< all-0xFF block (start-up)
    static constexpr int ERR_TIMEOUT = -4;  ///< no new data within 4 s
    static constexpr int ERR_ARG = -5;      ///< argument out of range

    static constexpr uint8_t CMD_READ = 0x00;      ///< measurement block (37 bytes)
    static constexpr uint8_t CMD_DATA = 0x40;      ///< new data flag
    static constexpr uint8_t CMD_SLEEP = 0x50;     ///< sleep (0x00) / wake up (0x80)
    static constexpr uint8_t CMD_CLEAN = 0x51;     ///< start fan cleaning (0x01)
    static constexpr uint8_t CMD_RESET = 0x52;     ///< reset (0x81)
    static constexpr uint8_t CMD_MAVE = 0x53;      ///< moving averages 1–60
    static constexpr uint8_t CMD_TINTC_H = 0x5A;   ///< cleaning interval high byte (30 s units)
    static constexpr uint8_t CMD_TINTC_L = 0x5B;   ///< cleaning interval low byte
    static constexpr uint8_t CMD_TCLEAN = 0x5C;    ///< cleaning time 0–60 s
    static constexpr uint8_t CMD_SPEEDFAN = 0x63;  ///< fan speed 60–100 %
    static constexpr uint8_t CMD_RMOX = 0x71;      ///< MOX resistance (4 bytes)
    static constexpr uint8_t CMD_UID = 0x72;       ///< unique ID (6 bytes)
    static constexpr uint8_t CMD_ARGVER = 0x73;    ///< algorithm version (3 bytes)
    static constexpr uint8_t CMD_CSTATUS = 0x74;   ///< cleaning status
    static constexpr uint8_t CMD_FWVER = 0x75;     ///< firmware version (2 bytes)

    static constexpr size_t BLOCK_LENGTH = 37;     ///< measurement block length

    /** @brief Construct the driver and start the start-up timers (no bus access).
     *  @param connection RegisterConnection bound to address 0x69.
     *  @param accessIntervalMs Minimum spacing between two accesses (default 1000 ms).
     */
    explicit RRH62000Minimal(RegisterConnection& connection, uint32_t accessIntervalMs = 1000);
    virtual ~RRH62000Minimal() = default;

    /** @brief Wait for the start-up time and read the firmware version as a presence check.
     *  @return true on success.
     */
    bool init();

    /** @brief Wait for a new sample (DATA polled once per second, up to 4 s) and decode it.
     *  @param out Receives PM (KCl), temperature, humidity, TVOC, eCO₂ and IAQ.
     *  @return true on success; false on a bus error, timeout, CRC error or start-up block.
     */
    bool read(RRH62000Measurement& out);

    /** @brief Reason of the last failure (one of the ERR_* values, 0 = none). */
    int last_error() const { return _error; }

    /** @brief CRC-8 of the block (polynomial 0x31, init 0xFF; CRC(0xBEEF) = 0x92).
     *  @param data Bytes to check.
     *  @param len Number of bytes.
     *  @return The CRC.
     */
    static uint8_t crc8(const uint8_t* data, size_t len);

    /** @brief Decode a 37-byte measurement block.
     *  @param block The raw block.
     *  @param out Receives the decoded values.
     *  @return 0 on success, ERR_INVALID for an all-0xFF block, ERR_CRC on a CRC mismatch.
     */
    static int decode(const uint8_t* block, RRH62000Data& out);

protected:
    RegisterConnection& _connection;
    uint32_t _interval;
    uint32_t _lastAccess = 0;
    uint32_t _validAt = 0;
    int _error = 0;

    static uint32_t _now();
    void _markStartup();
    void _waitAccess();
    bool _read(uint8_t cmd, uint8_t* buf, size_t len);
    bool _write(uint8_t cmd, uint8_t value);
    bool _readByte(uint8_t cmd, uint8_t& value);
    bool _readBlock(bool wait, RRH62000Data& out);
    bool _fail(int error) { _error = error; return false; }
};

/** @brief RRH62000 full interface — extends RRH62000Minimal with the complete block, status
 *  flags, particle bins, sleep, fan cleaning, reset, settings (moving average, fan speed,
 *  cleaning interval and time), MOX resistance and identification.
 *
 *  @param connection RegisterConnection (1-byte register addresses) bound to address 0x69.
 *  @param accessIntervalMs Minimum spacing between two accesses (default 1000 ms).
 */
class RRH62000Full : public RRH62000Minimal {
public:
    /** @brief Construct the driver (no bus access).
     *  @param connection RegisterConnection bound to address 0x69.
     *  @param accessIntervalMs Minimum spacing between two accesses (default 1000 ms).
     */
    explicit RRH62000Full(RegisterConnection& connection, uint32_t accessIntervalMs = 1000)
        : RRH62000Minimal(connection, accessIntervalMs) {}

    /** @brief Wait for a new sample and decode the complete block.
     *  @param out Receives status, particles, temperature, humidity, TVOC, eCO₂ and IAQ.
     *  @return true on success.
     */
    bool read_all(RRH62000Data& out);

    /** @brief Read the current block (without waiting for new data) and decode its status word.
     *  @param out Receives the status flags.
     *  @return true on success.
     */
    bool read_status(RRH62000Status& out);

    /** @brief Read the current block (without waiting for new data) and return the particle values.
     *  @param out Receives the six PM values and the five number concentrations.
     *  @return true on success.
     */
    bool read_particles(RRH62000Particles& out);

    /** @brief Check the DATA flag (0x40).
     *  @return 1 when new data has arrived, 0 when not, negative on error.
     */
    int data_ready();

    /** @brief Put the module to sleep (0x50 ← 0x00).
     *  @return true on success.
     */
    bool sleep();

    /** @brief Wake the module up (0x50 ← 0x80) and restart the start-up timers.
     *  @return true on success.
     */
    bool wake();

    /** @brief Start the fan dust-cleaning (0x51 ← 0x01).
     *  @return true on success.
     */
    bool start_cleaning();

    /** @brief Read the cleaning status (0x74).
     *  @return 1 when completed, 0 when not, negative on error.
     */
    int cleaning_done();

    /** @brief Reset the module (0x52 ← 0x81, same as power-on) and restart the start-up timers.
     *  A missing acknowledge during the reset is ignored.
     *  @return true.
     */
    bool soft_reset();

    /** @brief Set the number of moving averages (0x53).
     *  @param count 1–60 (default 10).
     *  @return true on success; false with ERR_ARG when out of range.
     */
    bool set_moving_average(uint8_t count);

    /** @brief Read the number of moving averages.
     *  @return 1–60, negative on error.
     */
    int get_moving_average();

    /** @brief Set the fan speed (0x63).
     *  @param percent 60–100 % (default 86).
     *  @return true on success; false with ERR_ARG when out of range.
     */
    bool set_fan_speed(uint8_t percent);

    /** @brief Read the fan speed.
     *  @return Percent (60–100), negative on error.
     */
    int get_fan_speed();

    /** @brief Set the automatic cleaning interval (0x5A/0x5B, 16-bit count of 30 s units).
     *  @param seconds 0–1 814 400 s, a multiple of 30 (default 604 800 = 7 days).
     *  @return true on success; false with ERR_ARG when out of range or not a multiple of 30.
     */
    bool set_cleaning_interval(uint32_t seconds);

    /** @brief Read the automatic cleaning interval.
     *  @return Seconds, negative on error.
     */
    int32_t get_cleaning_interval();

    /** @brief Set the fan auto-cleaning time (0x5C).
     *  @param seconds 0–60 s (default 10).
     *  @return true on success; false with ERR_ARG when out of range.
     */
    bool set_cleaning_time(uint8_t seconds);

    /** @brief Read the fan auto-cleaning time.
     *  @return Seconds (0–60), negative on error.
     */
    int get_cleaning_time();

    /** @brief Read the MOX sensor resistance (0x71, 4 bytes big-endian).
     *  @param ohms Receives the resistance in Ω.
     *  @return true on success.
     */
    bool read_mox_resistance(uint32_t& ohms);

    /** @brief Read the unique ID (0x72).
     *  @param uid Receives 6 bytes.
     *  @return true on success.
     */
    bool unique_id(uint8_t uid[6]);

    /** @brief Read the TVOC algorithm version (0x73).
     *  @param major Major version.
     *  @param minor Minor version.
     *  @param patch Patch version.
     *  @return true on success.
     */
    bool algorithm_version(uint8_t& major, uint8_t& minor, uint8_t& patch);

    /** @brief Read the firmware version (0x75).
     *  @param major Major version.
     *  @param minor Minor version.
     *  @return true on success.
     */
    bool firmware_version(uint8_t& major, uint8_t& minor);
};
