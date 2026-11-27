#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/Connection.h"
#include "../../connection/InputPin.h"

/** @brief A decoded RRH46410 measurement (Get Measurement Results). */
struct RRH46410Result {
    uint8_t sample;        ///< sample counter (0–255, wraps)
    float iaq;             ///< IAQ index, UBA level 1.0–5.0 (0 in PBAQ mode)
    float tvoc_mg_m3;      ///< TVOC in mg/m³
    float etoh_ppm;        ///< ethanol equivalent in ppm
    uint16_t eco2_ppm;     ///< estimated CO₂ in ppm (0 in PBAQ mode)
    uint16_t relative_iaq; ///< relative IAQ (0 in PBAQ mode)
    bool warmed_up;        ///< true after the warm-up samples of the mode
    bool pbaq;             ///< true when decoded in PBAQ mode (only TVOC and ethanol valid)
};

/** @brief The decoded status byte of the last response (RRH46410Full::status()). */
struct RRH46410Status {
    uint8_t raw;           ///< raw status byte
    bool invalid_command;  ///< bit 7: invalid command, read without request or bus error
    bool checksum_error;   ///< bit 6: request checksum wrong
    bool sensor_nack;      ///< bit 5: internal gas-sensor I²C NACK
    bool not_complete;     ///< bit 4: asynchronous command not complete
    uint8_t mode_error;    ///< bits 3:0: operation-mode error (0 OK, 0xA sensor damaged)
};

/** @brief RRH46410 indoor air quality gas sensor module (Renesas) — minimal interface.
 *
 *  One call returns the IAQ index, TVOC, ethanol equivalent, estimated CO₂ and relative IAQ
 *  of the IAQ 2nd Gen algorithm.
 *
 *  The host sends a request `[command, params…, checksum]` in one write and reads the response
 *  `[status, params…, checksum]` in a separate read; `checksum = ~(sum of the other bytes)`.
 *  The module stretches the I²C clock.
 *
 *  init() retries Get Product ID for up to 1 s, verifies 0x2310 and selects the IAQ 2nd Gen
 *  mode (one sample every 3 s; the first 100 samples, about 5 minutes, are the warm-up). The
 *  module's default humidity (50 %RH) is kept and the GPIOs are unused.
 *
 *  Errors: methods return false (or a negative value) and last_error() gives the reason — a
 *  positive status byte or one of the negative ERR_* values.
 *
 *  @param connection Plain I²C connection bound to address 0x38. The constructor does no bus
 *  access.
 */
class RRH46410Minimal {
public:
    static constexpr int ERR_BUS = -1;        ///< I²C transfer failed
    static constexpr int ERR_CHECKSUM = -2;   ///< response checksum wrong
    static constexpr int ERR_TIMEOUT = -3;    ///< no answer / no new sample within the timeout
    static constexpr int ERR_PRODUCT_ID = -4; ///< product ID is not 0x2310
    static constexpr int ERR_ARG = -5;        ///< invalid argument
    static constexpr int ERR_NOT_READY = -6;  ///< no result of the current mode yet

    static constexpr uint8_t MODE_SUSPEND = 0x00;     ///< no measurement (default after power-up)
    static constexpr uint8_t MODE_IAQ_2ND_GEN = 0x01; ///< IAQ 2nd Gen, sample every 3 s
    static constexpr uint8_t MODE_ULP = 0x02;         ///< ultra-low power, sample every 90 s
    static constexpr uint8_t MODE_PBAQ = 0x05;        ///< public-building air quality, every 5 s
    static constexpr uint8_t MODE_CLEANING = 0x80;    ///< one-time sensor cleaning (1 minute)

    static constexpr uint16_t PRODUCT_ID = 0x2310;    ///< expected Get Product ID value

    explicit RRH46410Minimal(Connection& connection);

    /** @brief Wait for the module, verify the product ID and start IAQ 2nd Gen mode.
     *  @return true on success.
     */
    bool init();

    /** @brief Wait for the next sample (sample period plus 2 s) and decode it.
     *  @param[out] result The decoded measurement.
     *  @return true on success.
     */
    bool read(RRH46410Result& result);

    /** @brief Reason of the last failure: 0 none, > 0 status byte, < 0 an ERR_* value. */
    int last_error() const { return _lastError; }

protected:
    static constexpr uint8_t CMD_GET_PRODUCT_ID = 0x00;
    static constexpr uint8_t CMD_GET_TRACKING_NUMBER = 0x01;
    static constexpr uint8_t CMD_GET_OPERATION_MODE = 0x10;
    static constexpr uint8_t CMD_SET_OPERATION_MODE = 0x11;
    static constexpr uint8_t CMD_SET_HUMIDITY = 0x12;
    static constexpr uint8_t CMD_GET_MEASUREMENT_RESULTS = 0x18;
    static constexpr uint8_t CMD_CONFIG_GPIO = 0x20;
    static constexpr uint8_t CMD_GET_GPIO = 0x21;
    static constexpr uint8_t CMD_SET_GPIO = 0x22;
    static constexpr uint8_t CMD_CLEAR_GPIO = 0x23;
    static constexpr uint8_t CMD_RESET = 0x8F;

    static constexpr uint8_t STATUS_NOT_COMPLETE = 0x10;
    static constexpr uint32_t POLL_MS = 200;
    static constexpr size_t MAX_RESPONSE = 16;

    Connection& _connection;
    int _lastError = 0;
    uint8_t _mode = MODE_SUSPEND;
    uint8_t _lastStatus = 0;
    bool _haveCounter = false;
    uint8_t _lastCounter = 0;
    uint32_t _samples = 0;
    RRH46410Result _last = {};

    static uint8_t _checksum(const uint8_t* data, size_t len);
    static float _period(uint8_t mode);
    static uint8_t _warmup(uint8_t mode);
    /** Send a request and read status + responseLen bytes; with check a non-zero status fails. */
    bool _transact(uint8_t command, const uint8_t* params, size_t len, uint8_t* out, size_t responseLen,
                   bool check = true);
    bool _waitProductId(uint8_t* data);
    bool _setMode(uint8_t mode);
    /** 1 = result in data, 0 = no result yet, -1 = error. */
    int _fetch(uint8_t* data);
    void _decode(const uint8_t* data, RRH46410Result& result);
    bool _waitSample(uint32_t timeoutMs);
};

/** @brief RRH46410 — full interface.
 *
 *  Adds identity, operation modes (IAQ 2nd Gen, ULP, PBAQ, cleaning), humidity compensation,
 *  GPIOs, status, reset, raw commands and the INT interrupt API. INT is active high,
 *  push-pull: high when a new result is available, low after Get Measurement Results.
 */
class RRH46410Full : public RRH46410Minimal {
public:
    explicit RRH46410Full(Connection& connection) : RRH46410Minimal(connection) {}

    /** @brief Get Product ID.
     *  @param[out] productId 0x2310.
     *  @param[out] firmware  Three bytes: major, minor, patch.
     *  @return true on success.
     */
    bool product_id(uint16_t& productId, uint8_t firmware[3]);

    /** @brief Get Tracking Number (6 bytes) into @p out. @return true on success. */
    bool tracking_number(uint8_t out[6]);

    /** @brief Get Operation Mode. @return the mode (MODE_*), or -1 on an error. */
    int get_mode();

    /** @brief Set Operation Mode: MODE_SUSPEND, MODE_IAQ_2ND_GEN, MODE_ULP or MODE_PBAQ.
     *  Stops a running measurement, restarts the warm-up and sets INT low. Use clean_sensor()
     *  for the cleaning mode.
     *  @return true on success (false with ERR_ARG for other modes).
     */
    bool set_mode(uint8_t mode);

    /** @brief Stop measuring (SUSPEND, about 0.7 µA). @return true on success. */
    bool suspend();

    /** @brief Read and decode the latest result without waiting.
     *  @return true on success; false with ERR_NOT_READY before the first sample of the mode.
     */
    bool read_results(RRH46410Result& result);

    /** @brief Wait until the sample counter changes (or INT goes high); the new sample is
     *  stored and returned by last_result().
     *  @return true when a new sample arrived within @p timeoutMs.
     */
    bool wait_for_sample(uint32_t timeoutMs);

    /** @brief The result decoded by the last read(), read_results() or wait_for_sample(). */
    const RRH46410Result& last_result() const { return _last; }

    /** @brief Set Humidity: ambient relative humidity 0–100 %, sent as round(percent / 100 × 255).
     *  @return true on success.
     */
    bool set_humidity(float percent);

    /** @brief Sample period in seconds: 3 (IAQ 2nd Gen), 90 (ULP), 5 (PBAQ), 0 otherwise.
     *  @param mode Operation mode, or -1 for the current mode.
     */
    float sample_period(int mode = -1) const;

    /** @brief Warm-up samples: 100 (IAQ 2nd Gen), 10 (ULP), 60 (PBAQ), 0 otherwise.
     *  @param mode Operation mode, or -1 for the current mode.
     */
    uint8_t warmup_samples(int mode = -1) const;

    /** @brief UBA rating text: "very good" (< 2.0), "good", "medium", "poor", "bad" (≥ 5.0). */
    static const char* air_quality_label(float iaq);

    /** @brief Run the one-time 1-minute sensor cleaning and wait (up to 90 s) for SUSPEND.
     *  Execute it at most once in the sensor's lifetime and do not interrupt it.
     *  @param confirm Must be true (otherwise ERR_ARG).
     *  @return true when the module returned to SUSPEND.
     */
    bool clean_sensor(bool confirm = false);

    /** @brief Config GPIO: bit 0 GPIO1 output, bit 1 GPIO2 output (0–3). */
    bool config_gpio(uint8_t directions);
    /** @brief Get GPIO: bits 0–3 = GPIO1–4 levels, or -1 on an error. */
    int get_gpio();
    /** @brief Set GPIO: drive the outputs in @p mask (0–3) high. */
    bool set_gpio(uint8_t mask);
    /** @brief Clear GPIO: drive the outputs in @p mask (0–3) low. */
    bool clear_gpio(uint8_t mask);

    /** @brief The decoded status byte of the last response. */
    RRH46410Status status() const;

    /** @brief Reset (0x8F, no response), wait 500 ms and verify the product ID again; the
     *  module restarts in SUSPEND. @return true on success.
     */
    bool reset();

    /** @brief Raw request with checksums; the status byte is available from status().
     *  Reset (0x8F) sends the request only.
     *  @param out          Buffer for @p responseLen response parameter bytes (max 16).
     *  @return responseLen on success, -1 on a bus or checksum error.
     */
    int send_command(uint8_t command, const uint8_t* params = nullptr, size_t len = 0,
                     uint8_t* out = nullptr, size_t responseLen = 0);

    /** @brief True if the INT pin is high (pin wired) or the sample counter changed since the
     *  last decoded result (the result is not consumed).
     */
    bool poll_interrupt();

    /** @brief Subscribe to INT (rising edge): @p callback is called when a new result is
     *  available; read it with read_results() outside interrupt context. Without any pin (no
     *  @p intPin and none on the connection) nothing is delivered — poll with poll_interrupt().
     */
    void on_interrupt(void (*callback)(), InputPin* intPin = nullptr);

    /** @brief Unsubscribe from interrupt edges. */
    void off_interrupt();

private:
    void (*_callback)() = nullptr;
    InputPin* _intPinUsed = nullptr;
    static RRH46410Full* _activeInstance;
    static void _edgeTrampoline();
    bool _gpio(uint8_t command, uint8_t mask);
};
