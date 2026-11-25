#pragma once
#include <stdint.h>
#include "../../connection/RegisterConnection.h"
#include "../../connection/OutputPin.h"

/** @brief The sixteen MCP453X / 454X / 455X / 456X / 463X / 464X / 465X / 466X family members. */
enum class MCP4XXXPart : uint8_t {
    MCP4531, MCP4532, MCP4551, MCP4552, MCP4631, MCP4632, MCP4651, MCP4652,
    MCP4541, MCP4542, MCP4561, MCP4562, MCP4641, MCP4642, MCP4661, MCP4662
};

/** @brief Part descriptor: one row of the family table. */
struct MCP4XXXPartInfo {
    const char* name;     ///< Part name, e.g. "MCP4532".
    uint8_t channels;     ///< Number of resistor networks (1 or 2).
    uint16_t max_code;    ///< Maximum wiper code: 128 (7-bit) or 256 (8-bit).
    bool potentiometer;   ///< true for the X1 parts (terminal A on a pin).
    bool nonvolatile;     ///< true for the 454X / 456X / 464X / 466X parts (EEPROM).
    bool wp_pin;          ///< true for the MCP4641 / MCP4661 (hardware WP pin).
    uint8_t addr_min;     ///< Lowest 7-bit I²C address (all address pins low).
    uint8_t addr_max;     ///< Highest 7-bit I²C address.
};

/** @brief Decoded STATUS register of the non-volatile parts. */
struct MCP4XXXStatus {
    bool eeprom_busy;     ///< An EEPROM write cycle is in progress (EEWA).
    bool wiper_lock[2];   ///< WiperLock of network 0 and 1 (index 1 is false on single-network parts).
    bool write_protect;   ///< Hardware WP pin OR the nonvolatile write-protect bit.
};

/** @brief MCP4XXX family — minimal interface.
 *
 * One driver for all sixteen parts (digital potentiometers MCP4531 … MCP4662): a part
 * descriptor selects resolution (7-bit: max code 128, 8-bit: 256), number of networks,
 * pot / rheostat pinout, memory type and address range. A transaction starts with a command byte
 * `AD3 AD2 AD1 AD0 C1 C0 D9 D8` that plays the role of the register address of the
 * RegisterConnection; a read returns two bytes.
 *
 * Default behaviour:
 * - part MCP4532, RAB = 10 kΩ, RW = 75 Ω; wipers keep their power-up value until written
 * - no I²C traffic at construction (init() does the bus check)
 *
 * An invalid channel calls abort(), matching the library's convention for unrecoverable
 * misuse in exception-free builds.
 */
class MCP4XXXMinimal {
public:
    /**
     * @brief Construct the driver; performs no I²C traffic.
     * @param connection  RegisterConnection (I²C) bound to an address in the part's range.
     * @param part        Family member.
     * @param r_ab        End-to-end resistance RAB in ohms (5000, 10000, 50000 or 100000).
     * @param r_wiper     Wiper resistance RW in ohms (typically 75 Ω).
     */
    MCP4XXXMinimal(RegisterConnection& connection, MCP4XXXPart part = MCP4XXXPart::MCP4532,
                   float r_ab = 10000.0f, float r_wiper = 75.0f);

    /** @brief Descriptor row of a part. */
    static const MCP4XXXPartInfo& info(MCP4XXXPart part);

    /** @brief Default 7-bit I²C address (all address pins low): 0x2E (45X1), 0x58 (45X2 / 46X2) or 0x28 (46X1). */
    static uint8_t default_address(MCP4XXXPart part) { return info(part).addr_min; }

    /** @brief Bus check: reads the wiper register(s), TCON (and STATUS on non-volatile parts); no writes. */
    void init();

    /**
     * @brief Set the volatile wiper of a network.
     *
     * Clamps the code to [0, max code]. A network locked by WiperLock ignores the write.
     * @param code     Wiper position (0 = W at B, max code 128 / 256 = W at A).
     * @param channel  Network index, 0 or 1 (dual parts only).
     */
    void set_wiper(int code, uint8_t channel = 0);

    /**
     * @brief Read the volatile wiper of a network.
     * @param channel  Network index.
     * @return Wiper position 0 … max code (values above full scale are clamped).
     */
    uint16_t get_wiper(uint8_t channel = 0);

    /**
     * @brief Set the W–B resistance to the nearest reachable value.
     * @param ohms     Target wiper-to-B resistance in ohms (clamped to the reachable range).
     * @param channel  Network index.
     */
    void set_resistance(float ohms, uint8_t channel = 0);

    /**
     * @brief Read the W–B resistance for the current wiper position.
     * @param channel  Network index.
     * @return Resistance in ohms, N / max_code × RAB + RW.
     */
    float get_resistance(uint8_t channel = 0);

protected:
    static constexpr uint8_t ADDR_WIPER0 = 0x00;
    static constexpr uint8_t ADDR_NVWIPER0 = 0x02;
    static constexpr uint8_t ADDR_TCON = 0x04;
    static constexpr uint8_t ADDR_STATUS = 0x05;
    static constexpr uint8_t ADDR_DATA0 = 0x06;
    static constexpr uint8_t ADDR_WP = 0x0F;
    static constexpr uint8_t OP_WRITE = 0x00;
    static constexpr uint8_t OP_INC = 0x04;
    static constexpr uint8_t OP_DEC = 0x08;
    static constexpr uint8_t OP_READ = 0x0C;

    RegisterConnection& _connection;
    const MCP4XXXPartInfo& _desc;
    float _r_ab;
    float _r_wiper;

    void     _check_channel(uint8_t channel) const;
    uint16_t _read9(uint8_t address);
    void     _write9(uint8_t address, uint16_t value);
    uint16_t _read_wiper(uint8_t channel);
    int      _clamp(long code) const;
};

/** @brief MCP4XXX family — full interface.
 *
 * Extends MCP4XXXMinimal with ratio helpers, increment / decrement, shutdown and terminal
 * control, General Call, reset, raw access and — on the non-volatile parts only (marked **NV**;
 * they return false / -1 on the volatile parts) — the EEPROM wiper, ten general-purpose EEPROM
 * registers, STATUS, WiperLock and write protect.
 */
class MCP4XXXFull : public MCP4XXXMinimal {
public:
    /**
     * @brief Construct the driver; performs no I²C traffic.
     * @param connection     RegisterConnection (I²C) bound to an address in the part's range.
     * @param part           Family member.
     * @param r_ab           End-to-end resistance RAB in ohms.
     * @param r_wiper        Wiper resistance RW in ohms.
     * @param hvc            Optional OutputPin switching the external circuit that drives HVC/A0 to
     *                       8.5–12.5 V (needed only by set_wiper_lock() / set_write_protect()).
     * @param hvc_settle_ms  Delay in ms between asserting hvc and the first SCL edge.
     */
    MCP4XXXFull(RegisterConnection& connection, MCP4XXXPart part = MCP4XXXPart::MCP4532,
                float r_ab = 10000.0f, float r_wiper = 75.0f,
                OutputPin* hvc = nullptr, unsigned hvc_settle_ms = 1);

    /** @brief Number of resistor networks (1 or 2). */
    uint8_t channels() const { return _desc.channels; }
    /** @brief Number of wiper positions (129 or 257). */
    uint16_t steps() const { return _desc.max_code + 1; }
    /** @brief true for the X1 parts (terminal A on a pin). */
    bool is_potentiometer() const { return _desc.potentiometer; }
    /** @brief true for the 454X / 456X / 464X / 466X parts. */
    bool is_nonvolatile() const { return _desc.nonvolatile; }

    /** @brief Set the wiper to round(ratio × max code); ratio is clamped to 0.0–1.0. */
    void set_ratio(float ratio, uint8_t channel = 0);
    /** @brief Read the wiper position as a ratio: code / max code. */
    float get_ratio(uint8_t channel = 0);

    /** @brief W–A resistance (max code − N) / max code × RAB + RW (potentiometer parts only; -1 on rheostats). */
    float get_resistance_wa(uint8_t channel = 0);
    /** @brief Set the W–A resistance to the nearest reachable value (potentiometer parts only; false on rheostats). */
    bool set_resistance_wa(float ohms, uint8_t channel = 0);
    /** @brief Resistance of one step, RAB / max code, in ohms. */
    float get_resistance_step() const { return _r_ab / _desc.max_code; }

    /**
     * @brief Write both wipers of a dual part (two transactions).
     * @return false on a single-network part (nothing is written).
     */
    bool set_both(int code0, int code1);

    /**
     * @brief Increment the wiper by @p steps (continuous transactions, saturating at the max code).
     * @return New wiper code (read back).
     */
    uint16_t increment(unsigned steps = 1, uint8_t channel = 0);
    /** @brief Decrement the wiper by @p steps (saturating at 0); returns the new code (read back). */
    uint16_t decrement(unsigned steps = 1, uint8_t channel = 0);

    /** @brief Shut a network down (TCON.RxHW = 0): A disconnected, W connected to B; the wiper is kept. */
    void shutdown(uint8_t channel = 0);
    /** @brief Leave shutdown (TCON.RxHW = 1). */
    void resume(uint8_t channel = 0);
    /** @brief true if the network is in shutdown (read from TCON). */
    bool is_shutdown(uint8_t channel = 0);

    /** @brief Connect or disconnect the A, W and B terminals of a network (TCON.RxA / RxW / RxB). */
    void set_terminals(bool a = true, bool w = true, bool b = true, uint8_t channel = 0);
    /** @brief Read the terminal connection state of a network from TCON. */
    void get_terminals(uint8_t channel, bool& a, bool& w, bool& b);
    /** @brief Raw 9-bit TCON register. */
    uint16_t get_tcon();
    /** @brief Enable or disable the General Call address (TCON.GCEN). */
    void set_general_call(bool enabled);

    /**
     * @brief Emulate a power-on reset: write every volatile wiper with its power-up value (mid-scale,
     * or the EEPROM wiper on non-volatile parts) and TCON 0x1FF; networks locked by WiperLock are skipped.
     */
    void reset();

    /**
     * @brief Raw 9-bit read of a memory address the part has.
     * @return The value, or -1 if the part has no such address.
     */
    int read_register(uint8_t address);
    /** @brief Raw write of a writable memory address the part has (false if it has none). */
    bool write_register(uint8_t address, int value);

    /** @brief Change the resistance model used by the conversions (no I²C traffic); a negative @p r_wiper keeps RW. */
    void set_rab(float r_ab, float r_wiper = -1.0f);

    /** @brief **NV.** Read and decode STATUS; false on volatile parts. */
    bool read_status(MCP4XXXStatus& out);
    /** @brief **NV.** Poll STATUS.EEWA until the EEPROM write cycle finished (tWC 5 ms typ., 10 ms max); false on timeout. */
    bool wait_ready(unsigned timeout_ms = 20);
    /** @brief **NV.** Read the EEPROM wiper (power-up value), clamped to the max code; -1 on volatile parts. */
    int get_nv_wiper(uint8_t channel = 0);
    /** @brief **NV.** Write the EEPROM wiper (volatile wiper unchanged) and wait for the write cycle. */
    bool set_nv_wiper(int code, uint8_t channel = 0);
    /** @brief **NV.** Write the current volatile wiper to the EEPROM wiper; returns the stored code, -1 on volatile parts. */
    int store_wiper(uint8_t channel = 0);
    /** @brief **NV.** Read general-purpose EEPROM register @p index (0–9); -1 on volatile parts or a bad index. */
    int read_data(uint8_t index);
    /** @brief **NV.** Write general-purpose EEPROM register @p index (0–9, value 0–0x1FF; uses one write cycle). */
    bool write_data(uint8_t index, int value);
    /** @brief **NV.** STATUS.WLx of a network. */
    bool is_wiper_locked(uint8_t channel = 0);
    /** @brief **NV.** STATUS.WP (hardware WP pin OR the nonvolatile bit). */
    bool is_write_protected();
    /** @brief **NV, needs hvc.** Enable (decrement command) or disable (increment) WiperLock with a high-voltage command. */
    bool set_wiper_lock(bool locked, uint8_t channel = 0);
    /** @brief **NV, needs hvc.** Set or clear the nonvolatile EEPROM write-protect bit with a high-voltage command. */
    bool set_write_protect(bool enabled);

private:
    OutputPin* _hvc;
    unsigned _hvc_settle_ms;

    void _modify_tcon(const uint8_t* bits, const bool* on, uint8_t n);
    uint16_t _step(uint8_t op, unsigned steps, uint8_t channel);
    bool _hv_command(uint8_t address, bool decrement);
    bool _address_ok(uint8_t address, bool writable) const;
};
