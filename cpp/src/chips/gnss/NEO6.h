#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../transport/Transport.h"

/** @brief Transport variant the NEO-6 is wired to. */
enum class NEO6BusType : uint8_t {
    Uart,  ///< UART: read() blocks with a timeout; a timeout means "no byte yet".
    I2c,   ///< I2C (DDC): each byte is a random-read to register 0xFF.
    Spi,   ///< SPI: each byte is a full-duplex transfer with an empty write phase.
};

/** @brief u-blox NEO-6 GNSS receiver: NMEA position, altitude, and fix status.
 *
 *  Reads bytes from the transport and assembles complete NMEA sentences
 *  terminated by CR/LF. Works out of the box with the module's factory
 *  defaults (NMEA output at 9600 baud, 1 Hz, all standard sentences
 *  enabled) -- no chip-side configuration is sent.
 *
 *  A stray idle-filler byte (0xFF on I2C/SPI when the module has nothing
 *  queued) can never start a sentence (NMEA sentences start with '$'); if
 *  one lands mid-sentence during a buffer underrun, the resulting sentence
 *  simply fails its checksum and is discarded, same as any other corrupted
 *  sentence -- no bus-specific 0xFF filtering is needed.
 */
class NEO6Minimal {
public:
    /** @brief Construct the driver.
     *  @param transport UART, I2C, or SPI transport bound to the module.
     *  @param bus_type  Which transport variant @p transport is; default Uart.
     */
    explicit NEO6Minimal(Transport& transport, NEO6BusType bus_type = NEO6BusType::Uart);

    /** @brief Read available bytes and parse at most one complete NMEA sentence.
     *  @return true if a GGA sentence with a valid fix (fix status > 0) was
     *          parsed during this call.
     */
    bool update();

    /** @brief Latitude of the last valid fix.
     *  @return Decimal degrees, positive north; NAN until the first valid GGA fix.
     */
    float latitude() const { return _lat; }

    /** @brief Longitude of the last valid fix.
     *  @return Decimal degrees, positive east; NAN until the first valid GGA fix.
     */
    float longitude() const { return _lon; }

    /** @brief Height above mean sea level of the last valid fix.
     *  @return Meters; NAN until the first valid GGA fix.
     */
    float altitude() const { return _alt; }

    /** @brief GGA fix quality of the last parsed GGA sentence.
     *  @return 0 = no fix, 1 = GPS, 2 = DGPS.
     */
    int fix() const { return _fix; }

    /** @brief Number of satellites used in the last GGA fix.
     *  @return Satellite count (GGA field 7).
     */
    int satellites() const { return _satellites; }

protected:
    static constexpr uint8_t  SENTENCE_START = 0x24;  // '$'
    static constexpr uint8_t  CR             = 0x0D;
    static constexpr uint8_t  LF             = 0x0A;
    static constexpr size_t   MAX_SENTENCE   = 96;
    static constexpr size_t   MAX_FIELDS     = 20;

    Transport&  _transport;
    NEO6BusType _bus_type;

    uint8_t _buf[MAX_SENTENCE];
    size_t  _buf_len     = 0;
    bool    _in_sentence = false;

    float _lat = 0.0f / 0.0f;  // NAN
    float _lon = 0.0f / 0.0f;
    float _alt = 0.0f / 0.0f;
    int   _fix = 0;
    int   _satellites = 0;

    /** @brief Fetch one byte if available.
     *  @param out Set to the byte read.
     *  @return true if a byte was available and @p out was set; false if
     *          none was ready this call (not an error).
     */
    bool _tryReadByte(uint8_t& out);

    /** @brief Validate checksum, split fields, and dispatch a complete sentence.
     *  @return true if a GGA sentence with fix status > 0 was parsed.
     */
    bool _onSentence(const uint8_t* sentence, size_t len);

    /** @brief Parse a GGA sentence's fields into fix/satellites/lat/lon/alt.
     *  @return true if the fix status field is > 0.
     */
    bool _parseGga(char** fields, int nFields);

    /** @brief Hook for NEO6Full to parse additional sentence types (RMC/VTG).
     *         No-op in NEO6Minimal.
     */
    virtual void _handleExtra(const char* sentenceId, char** fields, int nFields) {}

    /** @brief Split a NUL-terminated, comma-separated body into up to
     *         MAX_FIELDS fields in place (replaces commas with NUL).
     *  @return Number of fields found.
     */
    static int _splitFields(char* body, char** fields, int maxFields);
};

/** @brief NEO-6 with UBX binary messaging, rate/platform configuration, and
 *         richer NMEA fields (speed, course, UTC time/date, HDOP).
 *
 *  Extends NEO6Minimal; all Minimal methods are inherited unchanged.
 */
class NEO6Full : public NEO6Minimal {
public:
    explicit NEO6Full(Transport& transport, NEO6BusType bus_type = NEO6BusType::Uart);

    /** @brief Speed over ground.
     *  @return Meters per second, converted from RMC/VTG; NAN until the
     *          first speed field is parsed.
     */
    float speed() const { return _speed; }

    /** @brief Course over ground.
     *  @return Degrees, 0-360, from RMC/VTG; NAN until the first course
     *          field is parsed.
     */
    float course() const { return _course; }

    /** @brief UTC time of the last GGA or RMC sentence.
     *  @return "hhmmss.ss"; empty string until the first sentence with a
     *          time field is parsed.
     */
    const char* utcTime() const { return _utcTime; }

    /** @brief UTC date of the last RMC sentence.
     *  @return "ddmmyy"; empty string until the first RMC sentence is parsed.
     */
    const char* utcDate() const { return _utcDate; }

    /** @brief Horizontal dilution of precision from the last GGA sentence.
     *  @return Unitless HDOP; NAN until the first GGA sentence with a
     *          populated HDOP field is parsed.
     */
    float hdop() const { return _hdop; }

    /** @brief Frame and write a UBX message (adds sync bytes, length, checksum).
     *  @param msgClass  UBX message class (e.g. 0x06 for CFG).
     *  @param msgId     UBX message ID within the class.
     *  @param payload   Message payload bytes; nullptr for an empty poll request.
     *  @param payloadLen Number of bytes in @p payload.
     */
    void sendUbx(uint8_t msgClass, uint8_t msgId, const uint8_t* payload = nullptr, size_t payloadLen = 0);

    /** @brief Send a poll request and capture the response payload.
     *  @param msgClass    UBX message class to poll.
     *  @param msgId       UBX message ID to poll.
     *  @param outPayload  Destination buffer for the response payload.
     *  @param outLen      Set to the number of bytes written to @p outPayload.
     *  @param maxLen      Capacity of @p outPayload.
     *  @return true on success; false on ACK-NAK or if no matching response
     *          arrives before the internal idle budget is spent.
     */
    bool pollUbx(uint8_t msgClass, uint8_t msgId, uint8_t* outPayload, size_t& outLen, size_t maxLen);

    /** @brief Set the navigation update rate via CFG-RATE.
     *  @param hz Update rate in Hz (1-5 Hz for standard NEO-6 models).
     */
    void setRate(int hz);

    /** @brief Set the dynamic platform model via CFG-NAV5.
     *  @param model Platform model code -- 0=portable, 2=stationary,
     *               3=pedestrian, 4=automotive, 5=sea, 6=airborne<1g,
     *               7=airborne<2g, 8=airborne<4g.
     */
    void setPlatform(uint8_t model);

    /** @brief Force a cold start via CFG-RST (clears almanac, ephemeris,
     *         and last known position). */
    void coldStart();

    /** @brief Persist the current configuration via CFG-CFG (saves to
     *         battery-backed RAM and flash, where available). */
    void saveConfig();

protected:
    static constexpr uint8_t UBX_SYNC1    = 0xB5;
    static constexpr uint8_t UBX_SYNC2    = 0x62;
    static constexpr uint8_t CLASS_ACK    = 0x05;
    static constexpr uint8_t ID_ACK_NAK   = 0x00;

    float _speed  = 0.0f / 0.0f;  // NAN
    float _course = 0.0f / 0.0f;
    float _hdop   = 0.0f / 0.0f;
    char  _utcTime[16] = {0};
    char  _utcDate[8]  = {0};

    void _handleExtra(const char* sentenceId, char** fields, int nFields) override;
    void _parseRmc(char** fields, int nFields);
    void _parseVtg(char** fields, int nFields);

    /** @brief Scan for and validate one UBX response frame matching
     *         wantClass/wantId, up to an internal idle/frame budget.
     *  @return true if a matching, checksum-valid frame was captured.
     */
    bool _readUbxResponse(uint8_t wantClass, uint8_t wantId,
                          uint8_t* outPayload, size_t& outLen, size_t maxLen);
};
