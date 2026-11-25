#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/RegisterConnection.h"
#include "../../connection/InputPin.h"

/** @brief Converted BLOCK_READ / AVG_BLOCK_READ / BLACK_BOX_READ telemetry. */
struct LM25066Block {
    uint16_t diagnostic;   ///< READ_DIAGNOSTIC_WORD value in the block
    float current;         ///< Input current in A
    float voltage_out;     ///< Output voltage in V
    float voltage_in;      ///< Input voltage in V
    float power;           ///< Input power in W
    float temperature;     ///< MOSFET temperature in °C
};

/** @brief All warning / fault thresholds; a disabled threshold is NAN. */
struct LM25066Limits {
    float vin_ov_warn;     ///< V
    float vin_uv_warn;     ///< V
    float vout_uv_warn;    ///< V
    float ot_warn;         ///< °C
    float ot_fault;        ///< °C
    float iin_warn;        ///< A
    float pin_warn;        ///< W
};

/** @brief LM25066 hot-swap controller / PMBus power monitor — minimal interface.
 *
 * Reads the monitored rail: input voltage, output voltage, input current, input power and MOSFET
 * temperature. The constructor performs no bus access and never writes the chip (OPERATION,
 * GATE_MASK and DEVICE_SETUP are left alone). Telemetry is PMBus DIRECT; the current and power
 * coefficients depend on the sense resistor and the CL pin setting.
 *
 * @param connection        RegisterConnection (I²C/SMBus, 1-byte command; address 0x10-0x17, 0x40-0x47, 0x50-0x5A).
 * @param rSenseMohm        Sense resistor in milliohms.
 * @param currentLimitHigh  false = CL pin at GND (25 mV), true = CL pin at VDD (46 mV).
 */
class LM25066Minimal {
public:
    explicit LM25066Minimal(RegisterConnection& connection, float rSenseMohm = 1.0f, bool currentLimitHigh = false);

    /** @brief Read the input voltage (READ_VIN). @return VIN in volts. */
    float voltage_in();
    /** @brief Read the output voltage (READ_VOUT). @return VOUT in volts. */
    float voltage_out();
    /** @brief Read the instantaneous input current (MFR_READ_IIN). @return Current in amperes. */
    float current();
    /** @brief Read the instantaneous input power (MFR_READ_PIN). @return Power in watts. */
    float power();
    /** @brief Read the external MOSFET temperature (READ_TEMPERATURE_1). @return Temperature in °C. */
    float temperature();

protected:
    static constexpr uint8_t CMD_OPERATION       = 0x01;
    static constexpr uint8_t CMD_CLEAR_FAULTS    = 0x03;
    static constexpr uint8_t CMD_CAPABILITY      = 0x19;
    static constexpr uint8_t CMD_VOUT_UV_WARN    = 0x43;
    static constexpr uint8_t CMD_OT_FAULT        = 0x4F;
    static constexpr uint8_t CMD_OT_WARN         = 0x51;
    static constexpr uint8_t CMD_VIN_OV_WARN     = 0x57;
    static constexpr uint8_t CMD_VIN_UV_WARN     = 0x58;
    static constexpr uint8_t CMD_STATUS_BYTE     = 0x78;
    static constexpr uint8_t CMD_STATUS_WORD     = 0x79;
    static constexpr uint8_t CMD_STATUS_VOUT     = 0x7A;
    static constexpr uint8_t CMD_STATUS_INPUT    = 0x7C;
    static constexpr uint8_t CMD_STATUS_TEMP     = 0x7D;
    static constexpr uint8_t CMD_STATUS_CML      = 0x7E;
    static constexpr uint8_t CMD_STATUS_MFR      = 0x80;
    static constexpr uint8_t CMD_READ_VIN        = 0x88;
    static constexpr uint8_t CMD_READ_VOUT       = 0x8B;
    static constexpr uint8_t CMD_READ_TEMP       = 0x8D;
    static constexpr uint8_t CMD_MFR_ID          = 0x99;
    static constexpr uint8_t CMD_MFR_MODEL       = 0x9A;
    static constexpr uint8_t CMD_MFR_REVISION    = 0x9B;
    static constexpr uint8_t CMD_READ_VAUX       = 0xD0;
    static constexpr uint8_t CMD_READ_IIN        = 0xD1;
    static constexpr uint8_t CMD_READ_PIN        = 0xD2;
    static constexpr uint8_t CMD_IIN_WARN        = 0xD3;
    static constexpr uint8_t CMD_PIN_WARN        = 0xD4;
    static constexpr uint8_t CMD_READ_PIN_PEAK   = 0xD5;
    static constexpr uint8_t CMD_CLEAR_PIN_PEAK  = 0xD6;
    static constexpr uint8_t CMD_GATE_MASK       = 0xD7;
    static constexpr uint8_t CMD_ALERT_MASK      = 0xD8;
    static constexpr uint8_t CMD_DEVICE_SETUP    = 0xD9;
    static constexpr uint8_t CMD_BLOCK_READ      = 0xDA;
    static constexpr uint8_t CMD_SAMPLES_FOR_AVG = 0xDB;
    static constexpr uint8_t CMD_READ_AVG_VIN    = 0xDC;
    static constexpr uint8_t CMD_READ_AVG_VOUT   = 0xDD;
    static constexpr uint8_t CMD_READ_AVG_IIN    = 0xDE;
    static constexpr uint8_t CMD_READ_AVG_PIN    = 0xDF;
    static constexpr uint8_t CMD_BLACK_BOX       = 0xE0;
    static constexpr uint8_t CMD_DIAGNOSTIC      = 0xE1;
    static constexpr uint8_t CMD_AVG_BLOCK_READ  = 0xE2;

    struct Coef { float m; float b; int8_t r; };

    uint16_t _read_word(uint8_t cmd);
    void     _write_word(uint8_t cmd, uint16_t value);
    uint8_t  _read_byte(uint8_t cmd);
    uint16_t _read_y(uint8_t cmd) { return _read_word(cmd) & 0x0FFF; }
    void     _set_cl(bool high);
    static float    _from_y(uint16_t y, const Coef& c);
    static uint16_t _to_y(float x, const Coef& c);
    static float    _temp_c(uint16_t word) { return (float)(int16_t)word / 16.0f; }

    RegisterConnection& _connection;
    float _rs;
    bool _clHigh = false;
    Coef _volt, _vaux, _amp, _watt, _temp;
};

/** @brief LM25066 full interface — averaging, limits, status, control, alerts.
 *
 * WARNING: operation_off() cuts the load rail and set_gate_mask() can stop hardware faults from
 * switching the MOSFET off (which may destroy it). Neither is ever called by the constructor.
 * SOURCE_* constants are ALERT_MASK bit positions (1 = masked).
 */
class LM25066Full : public LM25066Minimal {
public:
    static constexpr uint16_t SOURCE_VOUT_UV_WARN    = 0x8000;
    static constexpr uint16_t SOURCE_IIN_WARN        = 0x4000;
    static constexpr uint16_t SOURCE_VIN_UV_WARN     = 0x2000;
    static constexpr uint16_t SOURCE_VIN_OV_WARN     = 0x1000;
    static constexpr uint16_t SOURCE_POWER_GOOD      = 0x0800;
    static constexpr uint16_t SOURCE_OT_WARN         = 0x0400;
    static constexpr uint16_t SOURCE_PIN_OP_WARN     = 0x0100;
    static constexpr uint16_t SOURCE_MOSFET_SHORTED  = 0x0040;
    static constexpr uint16_t SOURCE_VIN_UV_FAULT    = 0x0020;
    static constexpr uint16_t SOURCE_VIN_OV_FAULT    = 0x0010;
    static constexpr uint16_t SOURCE_IIN_FAULT       = 0x0008;
    static constexpr uint16_t SOURCE_OT_FAULT        = 0x0004;
    static constexpr uint16_t SOURCE_CML_FAULT       = 0x0002;
    static constexpr uint16_t SOURCE_CB_FAULT        = 0x0001;

    explicit LM25066Full(RegisterConnection& connection, float rSenseMohm = 1.0f, bool currentLimitHigh = false);

    /** @brief True if the most recent block / string read had the expected count byte. */
    bool valid() const { return _valid; }

    /** @brief Read VAUX (READ_VAUX). @return Voltage at the pin in volts (0 ... 1.16 V). */
    float voltage_aux();
    /** @brief BLOCK_READ: time-aligned telemetry. */
    LM25066Block read_block();
    /** @brief AVG_BLOCK_READ: averaged telemetry. */
    LM25066Block read_average();
    /** @brief BLACK_BOX_READ: snapshot latched at the first SMBA since clear_faults(). */
    LM25066Block black_box();
    /** @brief Averaged VIN in volts. */
    float voltage_in_avg();
    /** @brief Averaged VOUT in volts. */
    float voltage_out_avg();
    /** @brief Averaged input current in amperes. */
    float current_avg();
    /** @brief Averaged input power in watts. */
    float power_avg();
    /** @brief Peak input power since reset / clear_power_peak(), in watts. */
    float power_peak();
    /** @brief Reset the peak-power register (CLEAR_PIN_PEAK, send byte). */
    void clear_power_peak();
    /** @brief Average over 2^exponent samples. @param exponent 0-12. @return false (nothing written) if out of range. */
    bool set_averaging(uint8_t exponent);
    /** @brief Read SAMPLES_FOR_AVG (the exponent). */
    uint8_t get_averaging();

    /** @brief VIN over-voltage warning in volts; NAN disables. */
    void set_vin_ov_warn(float volts);
    /** @brief VIN under-voltage warning in volts; NAN disables. */
    void set_vin_uv_warn(float volts);
    /** @brief VOUT under-voltage warning in volts; NAN disables. */
    void set_vout_uv_warn(float volts);
    /** @brief Over-temperature warning in °C; NAN disables. */
    void set_ot_warn(float celsius);
    /** @brief Over-temperature fault in °C; NAN disables. */
    void set_ot_fault(float celsius);
    /** @brief Input over-current warning in amperes; NAN disables. */
    void set_iin_warn(float amps);
    /** @brief Input over-power warning in watts; NAN disables. */
    void set_pin_warn(float watts);
    /** @brief Read all thresholds (NAN where disabled). */
    LM25066Limits get_limits();

    /** @brief STATUS_BYTE. */
    uint8_t status_byte();
    /** @brief STATUS_WORD. */
    uint16_t status_word();
    /** @brief STATUS_VOUT. */
    uint8_t status_vout();
    /** @brief STATUS_INPUT. */
    uint8_t status_input();
    /** @brief STATUS_TEMPERATURE. */
    uint8_t status_temperature();
    /** @brief STATUS_CML. */
    uint8_t status_cml();
    /** @brief STATUS_MFR_SPECIFIC. */
    uint8_t status_mfr();
    /** @brief READ_DIAGNOSTIC_WORD. */
    uint16_t diagnostic_word();
    /** @brief CLEAR_FAULTS (send byte); does not switch the MOSFET on. */
    void clear_faults();
    /** @brief OPERATION ← 0x80. Switches the load on. */
    void operation_on();
    /** @brief OPERATION ← 0x00. CUTS THE LOAD RAIL. */
    void operation_off();
    /** @brief True if OPERATION reads 0x80 and the STATUS_BYTE OFF bit is clear. */
    bool is_on();

    /** @brief Read DEVICE_SETUP. */
    uint8_t device_setup();
    /** @brief Override the RETRY / CL / CB pin settings from software.
     *  @param retryCode        0-7 (bits 7:5), or -1 to leave under pin control.
     *  @param currentLimitHigh 1 = 46 mV, 0 = 25 mV, -1 = leave to pin; updates the coefficients when given.
     *  @param cbRatioHigh      1 = 3.6x, 0 = 1.8x, -1 = leave to pin.
     *  @return false (nothing written) if retryCode is out of range. */
    bool configure_device(int8_t retryCode = -1, int8_t currentLimitHigh = -1, int8_t cbRatioHigh = -1);
    /** @brief Write GATE_MASK. DANGEROUS: can disable fault shut-off of the MOSFET.
     *  @param mask Bits 5, 4, 2 only. @return false (nothing written) if other bits are set. */
    bool set_gate_mask(uint8_t mask);
    /** @brief Read GATE_MASK. */
    uint8_t get_gate_mask();
    /** @brief Read CAPABILITY. */
    uint8_t capability();
    /** @brief MFR_ID as a NUL-terminated string. @param buf Output (at least 4 bytes). @param len Size of buf. */
    void manufacturer_id(char* buf, size_t len);
    /** @brief MFR_MODEL as a NUL-terminated string. @param buf Output (at least 9 bytes). @param len Size of buf. */
    void model(char* buf, size_t len);
    /** @brief MFR_REVISION as a NUL-terminated string. @param buf Output (at least 3 bytes). @param len Size of buf. */
    void revision(char* buf, size_t len);

    /** @brief Unmask alert source bit(s) in ALERT_MASK (read-modify-write). */
    void enable_interrupt(uint16_t source);
    /** @brief Mask alert source bit(s) in ALERT_MASK (read-modify-write). */
    void disable_interrupt(uint16_t source);
    /** @brief Subscribe to SMBA (falling edge).
     *  @param callback void(*)(uint16_t diagnostic); receives READ_DIAGNOSTIC_WORD.
     *  @param intPin   Optional InputPin overriding connection.intPin(). */
    void onInterrupt(void (*callback)(uint16_t diagnostic), InputPin* intPin = nullptr);
    /** @brief Unsubscribe. */
    void offInterrupt();
    /** @brief Read READ_DIAGNOSTIC_WORD (does not clear; call clear_faults()). */
    uint16_t pollInterrupt();

private:
    static LM25066Full* _activeInstance;
    static void _edgeTrampoline();
    LM25066Block _block(uint8_t cmd);
    void _text(uint8_t cmd, uint8_t n, char* buf, size_t len);
    void _set_limit(uint8_t cmd, float value, const Coef& c, uint16_t disabled);
    float _get_limit(uint8_t cmd, const Coef& c, uint16_t disabled);

    void (*_callback)(uint16_t) = nullptr;
    InputPin* _intPinUsed = nullptr;
    bool _valid = true;
};
