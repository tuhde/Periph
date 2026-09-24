#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/Connection.h"

/** @brief Shared base for ST's VL53 FlightSense Time-of-Flight ranging family.
 *
 *  Internal — never instantiated directly. VL53L0X and VL53L1X have
 *  different register maps (8-bit vs 16-bit register index), so this base
 *  holds no register addresses and no ranging logic, only the plumbing both
 *  chips share: big-endian register access with a 1- or 2-byte index, the
 *  bounded poll helper, the XSHUT boot wait, interrupt delivery via the
 *  connection's InputPin, the volatile re-addressing helper, and the
 *  family's shared constants. See specs/tof/_vl53_base.md.
 */
class VL53Base {
public:
    /** @brief Default 7-bit I²C address of every family member. */
    static constexpr uint8_t I2C_ADDRESS = 0x29;
    /** @brief Range < low threshold. */
    static constexpr uint8_t SOURCE_LEVEL_LOW        = 1;
    /** @brief Range > high threshold. */
    static constexpr uint8_t SOURCE_LEVEL_HIGH       = 2;
    /** @brief Range < low threshold or > high threshold. */
    static constexpr uint8_t SOURCE_OUT_OF_WINDOW    = 3;
    /** @brief A new measurement is available (driver default). */
    static constexpr uint8_t SOURCE_NEW_SAMPLE_READY = 4;
    /** @brief low ≤ range ≤ high (VL53L1X only). */
    static constexpr uint8_t SOURCE_IN_WINDOW        = 5;

    /** @brief Releases any interrupt subscription slot. */
    virtual ~VL53Base();

protected:
    static constexpr uint32_t TIMEOUT_MS = 500;
    /** @brief XSHUT-high → first I²C access; tBOOT ≤ 1.2 ms rounded up to the ms delay resolution. */
    static constexpr uint32_t BOOT_MS = 2;
    /** @brief Number of driver instances that can hold an interrupt subscription at once. */
    static constexpr int MAX_SUBSCRIBERS = 4;

    /** @brief @param indexBytes Register index width on the wire, 1 or 2. */
    VL53Base(Connection& connection, uint8_t indexBytes) : _connection(connection), _indexBytes(indexBytes) {}

    Connection& _connection;
    uint8_t _indexBytes;

    void _wrBlock(uint16_t reg, const uint8_t* data, size_t len);
    void _rdBlock(uint16_t reg, uint8_t* buf, size_t len);
    void _wr8(uint16_t reg, uint8_t value);
    uint8_t _rd8(uint16_t reg);
    void _wr16(uint16_t reg, uint16_t value);
    uint16_t _rd16(uint16_t reg);
    void _wr32(uint16_t reg, uint32_t value);
    uint32_t _rd32(uint16_t reg);

    /** @brief Poll @p predicate until true; false after TIMEOUT_MS. */
    template <typename Predicate>
    bool _waitUntil(Predicate predicate) {
        uint32_t start = _millisNow();
        while (true) {
            if (predicate()) return true;
            if ((uint32_t)(_millisNow() - start) > TIMEOUT_MS) return false;
        }
    }

    /** @brief Drive XSHUT high (if an enPin exists) and wait for the firmware to boot. */
    void _bootWait();

    /** @brief Write a new 7-bit address to @p reg; false (no write) outside 0x08–0x77. */
    bool _setAddressReg(uint16_t reg, uint8_t address);

    /** @brief Read and clear a pending interrupt; return its SOURCE_* value or 0. */
    virtual uint8_t _pollInterruptStatus() = 0;

    /** @brief Subscribe @p callback to GPIO1 falling edges (@p intPin or connection.intPin()). */
    void _subscribe(void (*callback)(uint8_t status), InputPin* intPin);
    /** @brief Detach the edge handler and clear the callback. */
    void _unsubscribe();

    static uint32_t _millisNow();

private:
    void (*_callback)(uint8_t status) = nullptr;
    InputPin* _intPinUsed = nullptr;
    int _slot = -1;

    void _handleEdge();

    static VL53Base* _subscribers[MAX_SUBSCRIBERS];
    template <int N>
    static void _trampoline() {
        if (_subscribers[N]) _subscribers[N]->_handleEdge();
    }
    static void (*const _trampolines[MAX_SUBSCRIBERS])();
};
