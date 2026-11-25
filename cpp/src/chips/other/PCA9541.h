#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/RegisterConnection.h"

/** @brief PCA9541 2-to-1 I²C-bus master selector (NXP) — minimal interface.
 *
 *  Two upstream masters share one downstream bus of slave devices through the PCA9541's pass
 *  gates; this driver is used by **one** of the two masters (the same software runs on both).
 *  There is no arbitration: whichever master writes its CONTROL register last before a STOP wins.
 *  Each master sees its own copy of the IE, CONTROL and ISTAT registers behind the same addresses.
 *  The 7-bit I²C address is 0x70 + A (A3..A0); prefer 0x70–0x77.
 *
 *  The constructor reads CONTROL as a bus check (see present()) and ISTAT once to clear stale
 *  flags; it writes nothing, so the connection state is never changed by initialising a master.
 *  The CONTROL byte to write depends on the read-only complement bits, so every change reads
 *  CONTROL first and applies the datasheet's Table 12 rule.
 *
 *  @param connection RegisterConnection (I²C, 1-byte command code) pointing at the device.
 */
class PCA9541Minimal {
public:
    /** @brief 7-bit I²C address with A3..A0 = 0. */
    static constexpr uint8_t I2C_ADDRESS = 0x70;

    explicit PCA9541Minimal(RegisterConnection& connection);

    /** @brief Whether the bus check in the constructor was acknowledged. */
    bool present() const { return _present; }

    /** @brief Report whether this master's channel is connected to the downstream bus.
     *  @return true if MYBUS equals NMYBUS and the bus is connected (BUSON differs from NBUSON). */
    bool hasControl();

    /** @brief Take the downstream bus.
     *
     *  Reads CONTROL and writes MYBUS / BUSON per the datasheet rule; the write ends with a STOP,
     *  which makes the switch. With @p busInit the chip first sends nine clocks, a NACK and a STOP
     *  on the downstream bus and flags BUSINIT in ISTAT.
     *  @param busInit   true to request bus initialization / recovery.
     *  @param timeoutMs Maximum time to wait for the switch, in milliseconds.
     *  @return true if this master has control afterwards, false if not within @p timeoutMs. */
    bool acquire(bool busInit = false, float timeoutMs = 20.0f);

    /** @brief Give the downstream bus to the other master (MYBUS = NOT NMYBUS; the bus stays connected). */
    void release();

protected:
    static constexpr uint8_t REG_IE      = 0x00;
    static constexpr uint8_t REG_CONTROL = 0x01;
    static constexpr uint8_t REG_ISTAT   = 0x02;
    static constexpr uint8_t REG_AI      = 0x10;
    static constexpr uint8_t C_MYBUS    = 0x01;
    static constexpr uint8_t C_NMYBUS   = 0x02;
    static constexpr uint8_t C_BUSON    = 0x04;
    static constexpr uint8_t C_NBUSON   = 0x08;
    static constexpr uint8_t C_BUSINIT  = 0x10;
    static constexpr uint8_t C_TESTON   = 0x40;
    static constexpr uint8_t C_NTESTON  = 0x80;

    RegisterConnection& _connection;
    bool _present = false;

    static void _delayMs(unsigned ms);
    uint8_t _readReg(uint8_t reg);
    void _writeReg(uint8_t reg, uint8_t value);
    static bool _hasControl(uint8_t c) { return ((c & C_MYBUS) != 0) == ((c & C_NMYBUS) != 0); }
    static bool _connected(uint8_t c) { return ((c & C_BUSON) != 0) != ((c & C_NBUSON) != 0); }
};

/** @brief PCA9541 full interface — extends Minimal with raw control access, connect / disconnect,
 *  bus recovery, interrupt masks, the INT functional test and interrupt delivery.
 *
 *  @param connection RegisterConnection (I²C, 1-byte command code) pointing at the device.
 */
class PCA9541Full : public PCA9541Minimal {
public:
    /** @brief ISTAT / interrupt source bits. */
    static constexpr uint8_t SOURCE_INT_IN        = 0x01;
    static constexpr uint8_t SOURCE_BUS_INIT      = 0x02;
    static constexpr uint8_t SOURCE_BUS_NOT_IDLE  = 0x04;
    static constexpr uint8_t SOURCE_BUS_LOST      = 0x08;
    static constexpr uint8_t SOURCE_TEST          = 0x40;
    static constexpr uint8_t SOURCE_OTHER_TEST    = 0x80;

    /** @brief Decoded CONTROL register. */
    struct Status {
        bool hasControl;         ///< MYBUS == NMYBUS (control, regardless of the connection)
        bool connected;          ///< BUSON != NBUSON (some master is connected)
        bool busInitRequested;   ///< BUSINIT
        bool ownTest;            ///< TESTON
        bool otherTest;          ///< NTESTON
    };

    explicit PCA9541Full(RegisterConnection& connection);

    /** @brief Read the raw CONTROL byte. */
    uint8_t getControl();

    /** @brief Whether the downstream bus is connected to some master (BUSON differs from NBUSON). */
    bool isConnected();

    /** @brief Decode the CONTROL register. */
    Status status();

    /** @brief Turn the connection on without changing ownership (BUSON = NOT NBUSON; MYBUS as read). */
    void connect();

    /** @brief Disconnect both masters from the downstream bus (BUSON = NBUSON; ownership bits left). */
    void disconnect();

    /** @brief Recover a hung downstream bus and take it (acquire with bus initialization).
     *  @param timeoutMs Maximum time to wait for BUSINIT, in milliseconds.
     *  @return true if this master has control afterwards. */
    bool recoverBus(float timeoutMs = 20.0f);

    /** @brief Write the IE register (true = masked, no interrupt); all-false re-enables every interrupt.
     *  @param busLost    Mask the "other master took the bus" interrupt.
     *  @param busNotIdle Mask the "bus non-idle at the switch" interrupt.
     *  @param busInit    Mask the "bus initialization done" interrupt.
     *  @param intIn      Mask the downstream INT_IN interrupt. */
    void setInterruptMask(bool busLost = false, bool busNotIdle = false, bool busInit = false, bool intIn = false);

    /** @brief Read the IE register (bit set = interrupt masked). */
    uint8_t getInterruptMask();

    /** @brief Drive the INT lines for the functional test; the bus bits are written back unchanged.
     *  @param own   true to drive this master's INT low (TESTON).
     *  @param other true to drive the other master's INT low (NTESTON). */
    void testInterrupt(bool own = false, bool other = false);

    /** @brief Read IE, CONTROL and ISTAT in one auto-increment transaction (clears the self-clearing ISTAT flags). */
    void readAll(uint8_t& ie, uint8_t& control, uint8_t& istat);

    /** @brief Enable an interrupt source (clear its mask bit in IE); the test sources need no enable.
     *  @param source One of SOURCE_INT_IN, SOURCE_BUS_INIT, SOURCE_BUS_NOT_IDLE, SOURCE_BUS_LOST. */
    void enableInterrupt(uint8_t source);

    /** @brief Disable an interrupt source (set its mask bit in IE). */
    void disableInterrupt(uint8_t source);

    /** @brief Read ISTAT (clears BUSLOST / BUSOK / BUSINIT).
     *  @return ISTAT byte; bits as the SOURCE_* constants. */
    uint8_t pollInterrupt();

    /** @brief Subscribe to this master's INT pin (falling edge).
     *
     *  Wires @p intPin->onEdge() (or connection.intPin() if @p intPin is nullptr). Without any
     *  InputPin, call service() periodically instead.
     *  @param callback void(*)(uint8_t status), called with the ISTAT byte that was read.
     *  @param intPin Optional InputPin overriding connection.intPin(). */
    void onInterrupt(void (*callback)(uint8_t status), InputPin* intPin = nullptr);

    /** @brief Unsubscribe and stop delivery. */
    void offInterrupt();

    /** @brief Poll ISTAT once and dispatch the callback (for use without an INT pin; also the INT edge handler). */
    void service();

protected:
    void (*_callback)(uint8_t status) = nullptr;
    InputPin* _intPinUsed = nullptr;

    static PCA9541Full* _activeInstance;
    static void _edgeTrampoline();
};
