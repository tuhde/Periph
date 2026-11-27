#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/Connection.h"
#include "../../connection/InputPin.h"

/** @brief Firmware information returned by PN532Full::firmware_version(). */
struct PN532FirmwareVersion {
    uint8_t ic;        ///< IC code, 0x32 for the PN532
    uint8_t version;   ///< firmware version
    uint8_t revision;  ///< firmware revision
    uint8_t support;   ///< raw support byte
    bool iso14443a;    ///< support bit 0
    bool iso14443b;    ///< support bit 1
    bool iso18092;     ///< support bit 2
};

/** @brief Decoded GetGeneralStatus response (per-target entries are skipped). */
struct PN532GeneralStatus {
    uint8_t error;      ///< last error code
    bool field;         ///< external RF field present
    uint8_t targets;    ///< number of active targets
    uint8_t sam_status; ///< SAM status byte
};

/** @brief A target found by InListPassiveTarget. */
struct PN532Target {
    uint8_t tg;          ///< logical target number
    uint16_t sens_res;   ///< SENS_RES (ATQA), first byte high; 0 for FeliCa / type B
    uint8_t sel_res;     ///< SEL_RES (SAK); 0 for FeliCa / type B
    uint8_t uid[10];     ///< NFCID1 (type A), NFCID2 (FeliCa) or PUPI (type B)
    uint8_t uid_len;     ///< valid bytes in uid
    uint8_t ats[32];     ///< ATS (type A), PAD + system code (FeliCa) or ATTRIB_RES (type B); truncated to 32 bytes
    uint8_t ats_len;     ///< valid bytes in ats
};

/** @brief PN532 NFC/RFID controller (NXP) — minimal interface.
 *
 *  Detects one ISO/IEC 14443 A card or tag at 106 kbps and returns its UID.
 *
 *  The host exchanges frames with the PN532 firmware: a command frame
 *  (`00 00 FF LEN LCS D4 code data DCS 00`), the chip's ACK frame and a response frame
 *  (`TFI 0xD5`, code + 1). Every I²C read starts with a status byte (bit 0 = frame ready).
 *  The driver polls that byte, reads the 6-byte header, sends a NACK so the chip resends the
 *  response and then reads exactly `1 + LEN + 7` bytes.
 *
 *  init() checks GetFirmwareVersion (IC 0x32), sets SAMConfiguration normal mode with the IRQ
 *  pin enabled and MxRtyPassiveActivation = 2 (so a read without a card returns). Each write
 *  is retried up to 10 times (2 ms apart) to wake the chip; the ACK is awaited 15 ms and the
 *  frame resent up to 3 times. Response data is limited to MAX_DATA bytes.
 *
 *  Errors: methods return false (or a negative count) and last_error() gives the reason —
 *  a positive PN532 status code or one of the negative ERR_* values.
 *
 *  @param connection Plain I²C connection bound to address 0x24 (I0 low, I1 high). The
 *  constructor does no bus access.
 */
class PN532Minimal {
public:
    static constexpr int ERR_BUS = -1;         ///< I²C transfer failed
    static constexpr int ERR_NO_ACK = -2;      ///< no ACK frame after 3 attempts
    static constexpr int ERR_TIMEOUT = -3;     ///< no response within the timeout
    static constexpr int ERR_FRAME = -4;       ///< bad preamble, checksum, TFI, code or length
    static constexpr int ERR_ERROR_FRAME = -5; ///< the chip answered an error frame
    static constexpr int ERR_IC = -6;          ///< GetFirmwareVersion IC is not 0x32
    static constexpr int ERR_ARG = -7;         ///< invalid argument

    static constexpr size_t MAX_DATA = 64;     ///< maximum command / response data bytes

    explicit PN532Minimal(Connection& connection);

    /** @brief Wake the chip, check the firmware and configure SAM and retries.
     *  @return true on success.
     */
    bool init();

    /** @brief Detect one ISO/IEC 14443 A card and return its UID; the target is released.
     *  @param[out] uid      Buffer of at least 10 bytes.
     *  @param timeoutMs     Response timeout in ms (default 1000); on a timeout the command is aborted.
     *  @return UID length (4, 7 or 10), or 0 when no card answered or on an error (see last_error()).
     */
    uint8_t read_uid(uint8_t* uid, uint32_t timeoutMs = 1000);

    /** @brief Reason of the last failure: 0 none, > 0 PN532 status code, < 0 an ERR_* value. */
    int last_error() const { return _lastError; }

protected:
    static constexpr uint8_t TFI_HOST = 0xD4;
    static constexpr uint8_t TFI_CHIP = 0xD5;
    static constexpr uint8_t TFI_ERROR = 0x7F;

    static constexpr uint8_t CMD_DIAGNOSE = 0x00;
    static constexpr uint8_t CMD_GET_FIRMWARE_VERSION = 0x02;
    static constexpr uint8_t CMD_GET_GENERAL_STATUS = 0x04;
    static constexpr uint8_t CMD_READ_REGISTER = 0x06;
    static constexpr uint8_t CMD_WRITE_REGISTER = 0x08;
    static constexpr uint8_t CMD_SAM_CONFIGURATION = 0x14;
    static constexpr uint8_t CMD_POWER_DOWN = 0x16;
    static constexpr uint8_t CMD_RF_CONFIGURATION = 0x32;
    static constexpr uint8_t CMD_IN_DATA_EXCHANGE = 0x40;
    static constexpr uint8_t CMD_IN_COMMUNICATE_THRU = 0x42;
    static constexpr uint8_t CMD_IN_DESELECT = 0x44;
    static constexpr uint8_t CMD_IN_LIST_PASSIVE_TARGET = 0x4A;
    static constexpr uint8_t CMD_IN_RELEASE = 0x52;
    static constexpr uint8_t CMD_IN_SELECT = 0x54;

    static constexpr uint8_t IC = 0x32;
    static constexpr unsigned WRITE_RETRIES = 10;
    static constexpr uint32_t ACK_TIMEOUT_MS = 15;
    static constexpr unsigned ACK_ATTEMPTS = 3;

    Connection& _connection;
    int _lastError = 0;
    uint8_t _buf[MAX_DATA + 16];

    bool _write(const uint8_t* data, size_t len);
    bool _ready();
    bool _wait(uint32_t timeoutMs);
    bool _start(uint8_t command, const uint8_t* data, size_t len);
    /** Read the response; returns data length (after the response code, copied to out) or -1.
     *  With noneOnTimeout a timeout returns -2 (command aborted) instead of failing. */
    int _response(uint8_t command, uint8_t* out, size_t outMax, uint32_t timeoutMs, bool noneOnTimeout = false);
    int _command(uint8_t command, const uint8_t* data, size_t len, uint8_t* out, size_t outMax,
                 uint32_t timeoutMs = 1000);
    int _list(uint8_t maxTargets, uint8_t baud, const uint8_t* initiatorData, size_t initLen,
              uint8_t* out, size_t outMax, uint32_t timeoutMs);
};

/** @brief PN532 NFC/RFID controller — full interface.
 *
 *  Adds target listing at every baud rate, data exchange, MIFARE Classic and NTAG helpers, RF
 *  and retry configuration, register access, power-down, the self test, raw commands and the
 *  IRQ interrupt API.
 */
class PN532Full : public PN532Minimal {
public:
    static constexpr uint8_t BAUD_106_A = 0x00; ///< 106 kbps ISO/IEC 14443 A
    static constexpr uint8_t BAUD_212_F = 0x01; ///< 212 kbps FeliCa
    static constexpr uint8_t BAUD_424_F = 0x02; ///< 424 kbps FeliCa
    static constexpr uint8_t BAUD_106_B = 0x03; ///< 106 kbps ISO/IEC 14443 B

    explicit PN532Full(Connection& connection);

    /** @brief GetFirmwareVersion.
     *  @param[out] fw IC, version, revision, support byte and its decoded bits.
     */
    bool firmware_version(PN532FirmwareVersion& fw);

    /** @brief GetGeneralStatus.
     *  @param[out] status Error, field present, number of targets, SAM status.
     */
    bool general_status(PN532GeneralStatus& status);

    /** @brief InListPassiveTarget.
     *  @param[out] targets      Array of at least @p maxTargets entries.
     *  @param baud              BAUD_106_A (default), BAUD_212_F, BAUD_424_F or BAUD_106_B.
     *  @param maxTargets        1 or 2.
     *  @param initiatorData     Optional InitiatorData (UID, FeliCa polling payload, AFI).
     *  @param initLen           Bytes in @p initiatorData.
     *  @param timeoutMs         Response timeout in ms; a timeout aborts the command and returns 0.
     *  @return Number of targets found (0 = none), or -1 on an error.
     */
    int list_passive_targets(PN532Target* targets, uint8_t baud = BAUD_106_A, uint8_t maxTargets = 1,
                             const uint8_t* initiatorData = nullptr, size_t initLen = 0,
                             uint32_t timeoutMs = 1000);

    /** @brief InDataExchange with an activated target.
     *  @param target Logical target number.
     *  @param data   Data to send; @param len its length.
     *  @param[out] out Received data; @param outMax its capacity.
     *  @return Received length, or -1 on an error (a non-zero status sets last_error() to the code).
     */
    int data_exchange(uint8_t target, const uint8_t* data, size_t len, uint8_t* out, size_t outMax);

    /** @brief InCommunicateThru: raw RF exchange with the current target.
     *  @return Received length, or -1 on an error.
     */
    int communicate_thru(const uint8_t* data, size_t len, uint8_t* out, size_t outMax);

    /** @brief InRelease; @param target 0 = all. */
    bool release(uint8_t target = 0);

    /** @brief InSelect a listed target. */
    bool select(uint8_t target = 0);

    /** @brief InDeselect (keeps the target's information); @param target 0 = all. */
    bool deselect(uint8_t target = 0);

    /** @brief Authenticate a MIFARE Classic sector (0x60 key A / 0x61 key B).
     *  @param target Logical target number.
     *  @param block  Any block of the sector.
     *  @param key    6-byte key.
     *  @param uid    Card UID; its last 4 bytes are used. @param uidLen its length (≥ 4).
     *  @param keyB   Use key B.
     */
    bool mifare_authenticate(uint8_t target, uint8_t block, const uint8_t* key, const uint8_t* uid,
                             size_t uidLen, bool keyB = false);

    /** @brief Read a 16-byte MIFARE Classic block (0x30). @param[out] out 16 bytes. */
    bool mifare_read_block(uint8_t target, uint8_t block, uint8_t* out);

    /** @brief Write a 16-byte MIFARE Classic block (0xA0). @param data 16 bytes. */
    bool mifare_write_block(uint8_t target, uint8_t block, const uint8_t* data);

    /** @brief Read four NTAG / Ultralight pages from @p page (0x30). @param[out] out 16 bytes. */
    bool ntag_read_page(uint8_t target, uint8_t page, uint8_t* out);

    /** @brief Write one 4-byte NTAG / Ultralight page (0xA2). @param data 4 bytes. */
    bool ntag_write_page(uint8_t target, uint8_t page, const uint8_t* data);

    /** @brief RFConfiguration item 0x01: RF field on/off and auto RF collision avoidance. */
    bool set_rf_field(bool on, bool autoRfca = false);

    /** @brief RFConfiguration item 0x05: retry counts, 0xFF = infinite.
     *  @param passiveActivation MxRtyPassiveActivation (default 2).
     *  @param atr MxRtyATR (default 0xFF). @param psl MxRtyPSL (default 1).
     */
    bool set_max_retries(uint8_t passiveActivation = 2, uint8_t atr = 0xFF, uint8_t psl = 1);

    /** @brief ReadRegister (16-bit address). @param[out] value Register value. */
    bool read_register(uint16_t address, uint8_t& value);

    /** @brief WriteRegister (16-bit address). */
    bool write_register(uint16_t address, uint8_t value);

    /** @brief PowerDown; @param wakeupSources WakeUpEnable mask (default 0x20 = I²C). */
    bool power_down(uint8_t wakeupSources = 0x20);

    /** @brief Diagnose self test.
     *  @param test 0x00 communication line (echo of an 8-byte pattern), 0x01 ROM, 0x02 RAM, ...
     *  @return true when the test passed; false when it failed or on an error.
     */
    bool self_test(uint8_t test = 0x00);

    /** @brief Send a raw command and read its response.
     *  @return Response data length (after the response code), or -1 on an error.
     */
    int send_command(uint8_t command, const uint8_t* data, size_t len, uint8_t* out, size_t outMax,
                     uint32_t timeoutMs = 1000);

    /** @brief Write a command frame and read its ACK, without waiting for the response. */
    bool start_command(uint8_t command, const uint8_t* data = nullptr, size_t len = 0);

    /** @brief Read the response to a command started with start_command().
     *  @return Response data length, or -1 on an error (a timeout aborts the command).
     */
    int read_response(uint8_t command, uint8_t* out, size_t outMax, uint32_t timeoutMs = 1000);

    /** @brief Poll the status byte every 1 ms until a frame is ready. */
    bool wait_ready(uint32_t timeoutMs);

    /** @brief True when the status byte reports a ready ACK or response frame. */
    bool poll_interrupt();

    /** @brief Subscribe to IRQ (falling edge): @p callback is called when a frame is ready;
     *  read it with read_response(). Without any pin (no @p intPin and none on the connection)
     *  nothing is delivered — poll with poll_interrupt().
     */
    void on_interrupt(void (*callback)(), InputPin* intPin = nullptr);

    /** @brief Unsubscribe from interrupt edges. */
    void off_interrupt();

private:
    void (*_callback)() = nullptr;
    InputPin* _intPinUsed = nullptr;
    static PN532Full* _activeInstance;
    static void _edgeTrampoline();
    int _status_command(uint8_t command, const uint8_t* data, size_t len, uint8_t* out, size_t outMax);
};
