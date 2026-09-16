#pragma once
#include <stdint.h>
#include "../../connection/Connection.h"

/** @brief HMC5883L 3-axis magnetometer — minimal interface.
 *
 * Reads magnetic field on all three axes in continuous mode with sensible
 * defaults baked in. No configuration required beyond the connection.
 *
 * Default behaviour (baked into Minimal):
 * - Averaging: 8 samples (MA=11)
 * - ODR: 15 Hz (DO=100)
 * - Gain: ±1.3 Ga (GN=001), 1090 LSb/Gauss
 * - Mode: continuous measurement
 *
 * @param connection  Configured I²C connection pointing at the device (fixed address 0x1E).
 */
class HMC5883LMinimal {
public:
    /**
     * @brief Construct and initialise the HMC5883L.
     * @param connection  I²C connection bound to the chip's address (0x1E).
     */
    HMC5883LMinimal(Connection& connection);

    /**
     * @brief Read magnetic field on all three axes.
     * @param x  Output X-axis field in Tesla.
     * @param y  Output Y-axis field in Tesla.
     * @param z  Output Z-axis field in Tesla.
     * @return true if all axes valid, false if any axis overflowed (raw == -4096).
     */
    bool magnetic_field(float& x, float& y, float& z);

protected:
    static constexpr uint8_t REG_CONFIG_A    = 0x00;
    static constexpr uint8_t REG_CONFIG_B    = 0x01;
    static constexpr uint8_t REG_MODE        = 0x02;
    static constexpr uint8_t REG_DATA_X_MSB  = 0x03;
    static constexpr uint8_t REG_STATUS      = 0x09;
    static constexpr uint8_t REG_ID_A        = 0x0A;
    static constexpr uint8_t REG_ID_B        = 0x0B;
    static constexpr uint8_t REG_ID_C        = 0x0C;

    static constexpr float GAIN_LSB_PER_GAUSS[8] = {
        1370.0f,  // GN=0: ±0.88 Ga
        1090.0f,  // GN=1: ±1.3 Ga (default)
        820.0f,   // GN=2: ±1.9 Ga
        660.0f,   // GN=3: ±2.5 Ga
        440.0f,   // GN=4: ±4.0 Ga
        390.0f,   // GN=5: ±4.7 Ga
        330.0f,   // GN=6: ±5.6 Ga
        230.0f,   // GN=7: ±8.1 Ga
    };

    Connection& _connection;
    uint8_t _gain;          // 0-7
    float _gain_lsb_per_gauss;

    void _init_minimal();
    uint8_t  _read_reg8(uint8_t reg);
    int16_t  _read_reg16(uint8_t reg);
    void     _write_reg8(uint8_t reg, uint8_t value);
    void     _read_data_burst(int16_t& raw_x, int16_t& raw_y, int16_t& raw_z);
    float    _raw_to_tesla(int16_t raw);
};

/** @brief HMC5883L full interface — extends HMC5883LMinimal with complete chip functionality.
 *
 * Adds configuration, single-shot mode, self-test, identification, and status access.
 *
 * @param connection  Configured I²C connection pointing at the device (fixed address 0x1E).
 */
class HMC5883LFull : public HMC5883LMinimal {
public:
    /**
     * @brief Construct and initialise the HMC5883L.
     * @param connection  I²C connection bound to the chip's address (0x1E).
     */
    HMC5883LFull(Connection& connection);

    /**
     * @brief Write Configuration Registers A and B.
     * @param odr        Data output rate in Hz (continuous mode). Valid: 0.75, 1.5, 3, 7.5, 15, 30, 75.
     * @param averaging  Samples averaged per output. Valid: 1, 2, 4, 8.
     * @param gain       Gain index 0–7.
     * @return true on success, false on invalid parameter.
     */
    bool configure(float odr, uint8_t averaging, uint8_t gain);

    /**
     * @brief Update the gain setting (GN bits in Config B).
     * @param gain  Gain index 0–7.
     * @return true on success, false on invalid parameter.
     */
    bool set_gain(uint8_t gain);

    /**
     * @brief Set the operating mode.
     * @param mode  'continuous', 'single', or 'idle'.
     * @return true on success, false on invalid parameter.
     */
    bool set_mode(const char* mode);

    /**
     * @brief Check if new measurement data is ready.
     * @return true if RDY bit is set in Status Register.
     */
    bool data_ready();

    /**
     * @brief Read the raw Status Register.
     * @return Raw STATUS register byte (RDY in bit 0, LOCK in bit 1).
     */
    uint8_t status();

    /**
     * @brief Take a single measurement in single-shot mode.
     * @param x  Output X-axis field in Tesla.
     * @param y  Output Y-axis field in Tesla.
     * @param z  Output Z-axis field in Tesla.
     * @return true if all axes valid, false if any axis overflowed.
     */
    bool single_measurement(float& x, float& y, float& z);

    /**
     * @brief Read the identification registers.
     * @param id_a  Output ID register A (expected 0x48 'H').
     * @param id_b  Output ID register B (expected 0x34 '4').
     * @param id_c  Output ID register C (expected 0x33 '3').
     */
    void identify(uint8_t& id_a, uint8_t& id_b, uint8_t& id_c);

    /**
     * @brief Run self-test with positive or negative bias.
     * @param positive  true for positive bias (MS=01), false for negative bias (MS=10).
     * @param x  Output X-axis deflection in Tesla.
     * @param y  Output Y-axis deflection in Tesla.
     * @param z  Output Z-axis deflection in Tesla.
     * @return true if all axes valid, false if any axis overflowed.
     */
    bool self_test(bool positive, float& x, float& y, float& z);
};