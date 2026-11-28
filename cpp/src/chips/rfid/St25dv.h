#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/RegisterConnection.h"
#include "../../connection/InputPin.h"

/** @brief Interrupt sources: bit layout of IT_STS_Dyn (0x2005). */
struct ST25DVSource {
    static constexpr uint8_t RF_USER       = 0x01;  ///< Manage GPO set/reset
    static constexpr uint8_t RF_ACTIVITY   = 0x02;  ///< RF access
    static constexpr uint8_t RF_INTERRUPT  = 0x04;  ///< Manage GPO interrupt request
    static constexpr uint8_t FIELD_FALLING = 0x08;  ///< RF field disappeared
    static constexpr uint8_t FIELD_RISING  = 0x10;  ///< RF field appeared
    static constexpr uint8_t RF_PUT_MSG    = 0x20;  ///< RF reader wrote a mailbox message
    static constexpr uint8_t RF_GET_MSG    = 0x40;  ///< RF reader read the mailbox message
    static constexpr uint8_t RF_WRITE      = 0x80;  ///< RF reader wrote to EEPROM
};

/** @brief Decoded MB_CTRL_Dyn (fast-transfer-mode mailbox control and status). */
struct ST25DVMailboxStatus {
    bool enabled;       ///< MB_EN: fast transfer mode active
    bool host_put;      ///< HOST_PUT_MSG: the host wrote the current message
    bool rf_put;        ///< RF_PUT_MSG: the RF reader wrote the current message
    bool host_miss;     ///< HOST_MISS_MSG: host message not read before the watchdog
    bool rf_miss;       ///< RF_MISS_MSG: RF message not read before the watchdog
    bool host_current;  ///< HOST_CURRENT_MSG: the host is reading the message
    bool rf_current;    ///< RF_CURRENT_MSG: the RF reader is reading the message
};

/** @brief ST25DV04K/16K/64K dynamic NFC tag — minimal interface: user memory as a byte store.
 *
 *  The chip answers on two I²C addresses with 16-bit register addresses: 0x53 (user memory,
 *  dynamic registers, mailbox) and 0x57 (system configuration). Both connections must be
 *  constructed with reg_bytes = 2 on the same bus.
 *
 *  init() reads MEM_SIZE, BLK_SIZE and IC_REF; no configuration is written (delivery state:
 *  area 1 = the whole memory, no protection, GPO_EN = 1, field-change interrupt only).
 *  Reads and writes are split into transfers of at most 16 bytes aligned to 16-byte
 *  boundaries (never crossing a 256-byte page or an area border, and within the Arduino
 *  Wire buffer); every write is completed by ACK polling (5 ms per 4-byte EEPROM page).
 *  Reads are retried up to 3 times while an RF reader holds the EEPROM.
 */
class ST25DVMinimal {
public:
    /** @brief Store the connections; no bus traffic (call init()).
     *  @param user            RegisterConnection at 0x53 (reg_bytes = 2).
     *  @param system          RegisterConnection at 0x57 (reg_bytes = 2).
     *  @param gpo_active_high true for -JF variants (CMOS GPO, rising edge), false for -IE (open drain, falling edge).
     */
    ST25DVMinimal(RegisterConnection& user, RegisterConnection& system, bool gpo_active_high = false);

    /** @brief Read MEM_SIZE, BLK_SIZE and IC_REF and derive the memory size.
     *  @return true if the system area answered.
     */
    bool init();

    /** @brief Read user memory.
     *  @param address Start byte address.
     *  @param buf     Destination, at least @p length bytes.
     *  @param length  Number of bytes.
     *  @return false if the range lies outside the memory or a transfer failed.
     */
    bool read(uint16_t address, uint8_t* buf, size_t length);

    /** @brief Write user memory in 16-byte-aligned pieces, ACK-polling after each.
     *  @param address Start byte address.
     *  @param data    Bytes to write.
     *  @param length  Number of bytes.
     *  @return false if the range lies outside the memory, a write was NACKed (protected area,
     *          mailbox active) or a write cycle did not complete.
     */
    bool write(uint16_t address, const uint8_t* data, size_t length);

protected:
    static constexpr uint16_t SYS_MEM_SIZE = 0x0014;
    static constexpr uint8_t  CHUNK = 16;
    static constexpr uint16_t WRITE_TIMEOUT_MS = 50;
    static constexpr uint8_t  READ_RETRIES = 3;

    RegisterConnection& _user;
    RegisterConnection& _system;
    bool _gpo_active_high;
    uint32_t _size = 0;
    uint8_t _ic_ref = 0;

    /** @brief Register read with retries; false after READ_RETRIES failures. */
    bool _read(RegisterConnection& c, uint16_t reg, uint8_t* buf, size_t len);
    /** @brief ACK poll until the device answers again or @p timeout_ms passes. */
    bool _ack_poll(RegisterConnection& c, uint16_t timeout_ms);
    bool _in_range(uint32_t address, uint32_t length) const;
};

/** @brief ST25DV full interface — identification, system area, password session, memory
 *  areas, GPO interrupts, energy harvesting, RF state, mailbox and a minimal NDEF writer.
 *
 *  System-area writes need an open I²C security session (present_password()). Bits 0-6 of
 *  GPO_CTRL_Dyn are read-only over I²C, so interrupt sources are configured in the static
 *  GPO register (session required); only GPO_EN switches at runtime without a session.
 */
class ST25DVFull : public ST25DVMinimal {
public:
    static constexpr uint8_t GPO_RF_USER      = 0x01;  ///< GPO level by Manage GPO set/reset
    static constexpr uint8_t GPO_RF_ACTIVITY  = 0x02;  ///< GPO follows RF activity
    static constexpr uint8_t GPO_RF_INTERRUPT = 0x04;  ///< GPO pulse by Manage GPO
    static constexpr uint8_t GPO_FIELD_CHANGE = 0x08;  ///< pulse on RF field change
    static constexpr uint8_t GPO_RF_PUT_MSG   = 0x10;  ///< pulse when the reader put a message
    static constexpr uint8_t GPO_RF_GET_MSG   = 0x20;  ///< pulse when the reader got the message
    static constexpr uint8_t GPO_RF_WRITE     = 0x40;  ///< pulse after an RF EEPROM write
    static constexpr uint8_t GPO_EN           = 0x80;  ///< GPO output enable

    /** @brief Same arguments as ST25DVMinimal. */
    ST25DVFull(RegisterConnection& user, RegisterConnection& system, bool gpo_active_high = false);

    /** @brief User memory size in bytes ((MEM_SIZE + 1) × (BLK_SIZE + 1)), valid after init(). */
    uint32_t memory_size() const { return _size; }

    /** @brief IC_REF: 0x24 (ST25DV04K) or 0x26 (ST25DV16K/64K), valid after init(). */
    uint8_t ic_reference() const { return _ic_ref; }

    /** @brief Read the 64-bit UID, most significant byte first (E0 02 …).
     *  @param out 8-byte destination.
     *  @return true on success.
     */
    bool uid(uint8_t out[8]);

    /** @brief Read IC_REV. @return IC revision (0 on bus error). */
    uint8_t ic_revision();

    /** @brief Raw read of the system configuration area (device 0x57).
     *  @param address System register address.
     *  @param buf     Destination.
     *  @param length  Number of bytes (≤ 32).
     *  @return true on success.
     */
    bool read_system(uint16_t address, uint8_t* buf, size_t length);

    /** @brief Raw write to the system area, then ACK poll (session required).
     *  @param address System register address.
     *  @param data    Bytes to write.
     *  @param length  Number of bytes (≤ 32).
     *  @return false if NACKed (no session, locked) or the write cycle did not complete.
     */
    bool write_system(uint16_t address, const uint8_t* data, size_t length);

    /** @brief Write one system register byte (session required).
     *  @param address System register address.
     *  @param value   Byte value.
     *  @return true on success.
     */
    bool write_system(uint16_t address, uint8_t value);

    /** @brief Present the I²C password (opens the security session).
     *  @param password 8 bytes, most significant first (factory 00 × 8).
     *  @return true if the session is open afterwards.
     */
    bool present_password(const uint8_t password[8]);

    /** @brief Change the I²C password (session must be open).
     *  @param password New 8-byte password, most significant first.
     *  @return false if no session is open or the write failed.
     */
    bool write_password(const uint8_t password[8]);

    /** @brief I2C_SSO_Dyn bit 0. @return true if the I²C security session is open. */
    bool session_open();

    /** @brief Set ENDA1..3 (session required); area n ends at byte end_block × 32 + 31.
     *  @param area      1-3.
     *  @param end_block ENDAn value 0-255.
     *  @return false on an invalid area or a failed write.
     */
    bool set_area_end(uint8_t area, uint8_t end_block);

    /** @brief Set RFAnSS and the area's I2CSS bits (session required).
     *  @param area 1-4.
     *  @param rf   RFAnSS 0-15 (bits 3:2 RW protection, bits 1:0 RF password).
     *  @param i2c  I2CSS level 0-3 (bit 0 write, bit 1 read protection).
     *  @return false on invalid arguments or a failed write.
     */
    bool set_area_protection(uint8_t area, uint8_t rf, uint8_t i2c);

    /** @brief Read the protection of an area.
     *  @param area 1-4.
     *  @param rf   Receives RFAnSS & 0x0F.
     *  @param i2c  Receives the area's I2CSS level.
     *  @return true on success.
     */
    bool get_area_protection(uint8_t area, uint8_t& rf, uint8_t& i2c);

    /** @brief Write the static GPO register (session required); copied into GPO_CTRL_Dyn.
     *  @param sources Mask of GPO_* enable bits 0-6.
     *  @param enable  Sets GPO_EN.
     *  @return true on success.
     */
    bool set_gpo(uint8_t sources, bool enable = true);

    /** @brief Read the static GPO register. @return GPO_* mask. */
    uint8_t get_gpo();

    /** @brief Switch the GPO output at runtime (GPO_CTRL_Dyn.GPO_EN, no session).
     *  @param enable true enables the output.
     *  @return true on success.
     */
    bool set_gpo_runtime(bool enable);

    /** @brief Set IT_TIME (session required); pulse = 301 µs − value × 37.65 µs.
     *  @param value 0-7.
     *  @return true on success.
     */
    bool set_interrupt_pulse(uint8_t value);

    /** @brief EH_CTRL_Dyn.FIELD_ON. @return true if an RF field is present. */
    bool field_present();

    /** @brief Set EH_CTRL_Dyn.EH_EN; write EH_MODE (session required) only when @p forced changes it.
     *  @param enabled Energy harvesting on now.
     *  @param forced  Forced after power-up (EH_MODE = 0) instead of on demand.
     *  @return true on success.
     */
    bool set_energy_harvesting(bool enabled, bool forced = false);

    /** @brief Set RF_MNGT_Dyn (runtime RF state; the static RF_MNGT is unchanged).
     *  @param disable RF_DISABLE.
     *  @param sleep   RF_SLEEP.
     *  @return true on success.
     */
    bool set_rf_state(bool disable = false, bool sleep = false);

    /** @brief Set MB_MODE if needed (session required), then MB_CTRL_Dyn.MB_EN. @return true on success. */
    bool enable_mailbox();

    /** @brief Clear MB_CTRL_Dyn.MB_EN (MB_MODE stays). @return true on success. */
    bool disable_mailbox();

    /** @brief Decode MB_CTRL_Dyn. @return Status (all false on a bus error). */
    ST25DVMailboxStatus mailbox_status();

    /** @brief Put a message into the mailbox (one write starting at 0x2008).
     *  On AVR Arduino boards the 32-byte Wire buffer limits a message to 30 bytes.
     *  @param data   Message bytes.
     *  @param length 1-256.
     *  @return false on an invalid length, a disabled or busy mailbox, or a NACK.
     */
    bool mailbox_write(const uint8_t* data, size_t length);

    /** @brief Read the mailbox message (MB_LEN_Dyn + 1 bytes).
     *  @param buf      Destination (256 bytes covers every message).
     *  @param capacity Size of @p buf.
     *  @return Message length, 0 if none, on error or if it does not fit.
     */
    size_t mailbox_read(uint8_t* buf, size_t capacity);

    /** @brief Set MB_WDG (session required); watchdog = 2^(value − 1) × 30 ms, 0 disables.
     *  @param value 0-7.
     *  @return true on success.
     */
    bool set_mailbox_watchdog(uint8_t value);

    /** @brief Write LOCK_CCFILE (session required): RF write protection of blocks 0 and 1.
     *  @param lock true locks both blocks.
     *  @return true on success.
     */
    bool lock_ccfile(bool lock = true);

    /** @brief Write a Type 5 capability container and one NDEF TLV (03 len … FE).
     *  @param message NDEF message bytes.
     *  @param length  Message length.
     *  @return false if it does not fit or a write failed.
     */
    bool write_ndef(const uint8_t* message, size_t length);

    /** @brief Read the message of the first NDEF TLV.
     *  @param buf      Destination.
     *  @param capacity Size of @p buf.
     *  @return Message length, 0 if no CC/TLV, on error or if it does not fit.
     */
    size_t read_ndef(uint8_t* buf, size_t capacity);

    /** @brief ACK poll on 0x53 until the write cycle has finished. @return false on timeout. */
    bool wait_write_complete();

    /** @brief Enable GPO sources in the static GPO register plus GPO_EN (session required).
     *  FIELD_RISING and FIELD_FALLING share FIELD_CHANGE_EN.
     *  @param source ST25DVSource mask.
     *  @return true on success.
     */
    bool enable_interrupt(uint8_t source);

    /** @brief Clear GPO sources in the static GPO register (session required).
     *  @param source ST25DVSource mask.
     *  @return true on success.
     */
    bool disable_interrupt(uint8_t source);

    /** @brief Read IT_STS_Dyn (cleared on read). @return ST25DVSource mask, 0 = none. */
    uint8_t poll_interrupt();

    /** @brief Subscribe to GPO edges (falling for -IE, rising for -JF); the callback receives
     *  the ST25DVSource mask. Without any pin nothing is delivered — poll with poll_interrupt().
     *  @param callback Called with the source mask.
     *  @param intPin   Optional pin overriding the user connection's intPin().
     */
    void on_interrupt(void (*callback)(uint8_t status), InputPin* intPin = nullptr);

    /** @brief Unsubscribe from GPO edges. */
    void off_interrupt();

private:
    static constexpr uint16_t SYS_GPO = 0x0000;
    static constexpr uint16_t SYS_IT_TIME = 0x0001;
    static constexpr uint16_t SYS_EH_MODE = 0x0002;
    static constexpr uint16_t SYS_RFA1SS = 0x0004;
    static constexpr uint16_t SYS_ENDA1 = 0x0005;
    static constexpr uint16_t SYS_I2CSS = 0x000B;
    static constexpr uint16_t SYS_LOCK_CCFILE = 0x000C;
    static constexpr uint16_t SYS_MB_MODE = 0x000D;
    static constexpr uint16_t SYS_MB_WDG = 0x000E;
    static constexpr uint16_t SYS_UID = 0x0018;
    static constexpr uint16_t SYS_IC_REV = 0x0020;
    static constexpr uint16_t SYS_I2C_PWD = 0x0900;
    static constexpr uint16_t DYN_GPO_CTRL = 0x2000;
    static constexpr uint16_t DYN_EH_CTRL = 0x2002;
    static constexpr uint16_t DYN_RF_MNGT = 0x2003;
    static constexpr uint16_t DYN_I2C_SSO = 0x2004;
    static constexpr uint16_t DYN_IT_STS = 0x2005;
    static constexpr uint16_t DYN_MB_CTRL = 0x2006;
    static constexpr uint16_t DYN_MB_LEN = 0x2007;
    static constexpr uint16_t MAILBOX = 0x2008;
    static constexpr uint16_t MAILBOX_SIZE = 256;

    void (*_callback)(uint8_t) = nullptr;
    InputPin* _intPinUsed = nullptr;
    static ST25DVFull* _activeInstance;
    static void _edgeTrampoline();
    void _handleEdge();

    bool _read_byte(RegisterConnection& c, uint16_t reg, uint8_t& value);
    bool _write_dyn(uint16_t reg, uint8_t value);
    bool _send_password(const uint8_t password[8], uint8_t code);
    static uint8_t _source_to_gpo(uint8_t source);
};
