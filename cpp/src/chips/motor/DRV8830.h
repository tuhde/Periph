#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/Connection.h"

/** @brief DRV8830 low-voltage motor driver with I²C interface — minimal interface.
 *
 *  H-bridge driver for a single brushed DC motor, controlled entirely over
 *  I²C. The host commands a target output *voltage*; the chip PWM-regulates
 *  the bridge to hold that average voltage regardless of supply sag. Nine
 *  selectable addresses (0x60–0x68) via the tri-state A0/A1 strap pins.
 *
 *  The constructor makes no bus access (safe for global construction before
 *  the bus is started); the chip's POR default already leaves the motor in
 *  standby/coast, so no register writes are needed before the first drive().
 *
 *  @param connection Configured I²C connection pointing at the device.
 */
class DRV8830Minimal {
public:
    /** @brief Default 7-bit I²C address (A0 = A1 = GND). Valid range 0x60–0x68. */
    static constexpr uint8_t I2C_ADDRESS = 0x60;

    /** @brief Internal reference voltage, typical (datasheet: 1.235–1.335 V). */
    static constexpr float VREF = 1.285f;
    /** @brief Lowest valid VSET code (0x00–0x05 are reserved). */
    static constexpr uint8_t VSET_MIN = 6;
    /** @brief Highest VSET code (≈5.06 V). */
    static constexpr uint8_t VSET_MAX = 63;

    explicit DRV8830Minimal(Connection& connection);

    /** @brief Drive the motor at a regulated output voltage.
     *
     *  Writes VSET and IN1/IN2 together in one CONTROL write. A magnitude
     *  below the ~0.48 V floor is treated as 0 V (coast); above ~5.06 V it is
     *  clamped to the maximum code.
     *
     *  @param voltage Signed target voltage in V — positive = forward,
     *                 negative = reverse, 0 = standby/coast.
     */
    void drive(float voltage);

    /** @brief Short-brake the motor (IN1 = IN2 = 1, both outputs high). */
    void brake();

    /** @brief Put the bridge in standby/coast (IN1 = IN2 = 0) — same as drive(0). */
    void stop();

protected:
    static constexpr uint8_t REG_CONTROL = 0x00;
    static constexpr uint8_t REG_FAULT   = 0x01;

    // CONTROL (0x00) bits.
    static constexpr uint8_t CTRL_IN1 = 0x01;
    static constexpr uint8_t CTRL_IN2 = 0x02;

    // FAULT (0x01) bits.
    static constexpr uint8_t FAULT_FAULT  = 0x01;
    static constexpr uint8_t FAULT_OCP    = 0x02;
    static constexpr uint8_t FAULT_UVLO   = 0x04;
    static constexpr uint8_t FAULT_OTS    = 0x08;
    static constexpr uint8_t FAULT_ILIMIT = 0x10;
    static constexpr uint8_t FAULT_CLEAR  = 0x80;

    Connection& _connection;

    void _writeReg(uint8_t reg, uint8_t value);
    uint8_t _readReg(uint8_t reg);

    /** @brief Map |voltage| to a VSET code; 0 means coast (below the floor). */
    static uint8_t _voltageToVset(float voltage);
    /** @brief VSET code to volts; 0 for a reserved code. */
    static float _vsetToVoltage(uint8_t vset);
};

/** @brief DRV8830 full interface — extends Minimal with raw CONTROL access,
 *  output read-back, fault reporting/clearing, and the FAULTn interrupt API.
 *
 *  Faults are never cleared implicitly: a latched OCP/ILIMIT fault also
 *  disables the H-bridge, so clearing is always an explicit clearFault().
 *
 *  @param connection Configured I²C connection pointing at the device.
 */
class DRV8830Full : public DRV8830Minimal {
public:
    /** @brief H-bridge state decoded from IN1/IN2. */
    enum class Direction : uint8_t {
        Coast   = 0,  ///< IN1=0, IN2=0 — outputs high-Z (standby)
        Forward = 1,  ///< IN1=1, IN2=0
        Reverse = 2,  ///< IN1=0, IN2=1
        Brake   = 3,  ///< IN1=1, IN2=1 — both outputs high
    };

    /** @brief Decoded CONTROL register. */
    struct Output {
        float voltage;        ///< Commanded magnitude in V (0 for a reserved VSET code)
        Direction direction;  ///< H-bridge state
    };

    /** @brief Decoded FAULT register. */
    struct Fault {
        bool fault;   ///< Any fault condition exists
        bool ocp;     ///< Overcurrent (short-circuit) event
        bool uvlo;    ///< Undervoltage lockout
        bool ots;     ///< Overtemperature shutdown
        bool ilimit;  ///< Extended current-limit event
    };

    explicit DRV8830Full(Connection& connection);

    /** @brief Write the CONTROL register from raw fields.
     *  @param vset VSET DAC code, 6–63; out-of-range codes are ignored (no write).
     *  @param in1  H-bridge input 1.
     *  @param in2  H-bridge input 2.
     *  @return true if written, false if vset was out of range. */
    bool setOutput(uint8_t vset, bool in1, bool in2);

    /** @brief Read back and decode the CONTROL register. */
    Output readOutput();

    /** @brief Read the FAULT register without clearing it. */
    Fault readFault();

    /** @brief Clear all fault status bits (CLEAR = 1); re-enables the
     *  H-bridge if an OCP/ILIMIT fault had latched it off. */
    void clearFault();

    /** @brief Subscribe to fault interrupts (FAULTn, active-low, falling edge).
     *  @param callback Called with the readFault() result; the fault is not cleared.
     *  @param intPin Optional InputPin for this call, overriding connection.intPin(). */
    void onInterrupt(void (*callback)(const Fault& fault), InputPin* intPin = nullptr);
    /** @brief Unsubscribe and stop delivery. */
    void offInterrupt();
    /** @brief Read the fault status — equivalent to readFault(), does not clear. */
    Fault pollInterrupt();

protected:
    void (*_callback)(const Fault& fault) = nullptr;
    InputPin* _intPinUsed = nullptr;

    static DRV8830Full* _activeInstance;
    static void _edgeTrampoline();
    void _handleEdge();
};
