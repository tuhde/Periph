#include <stdio.h>
#include <cmath>
#include "DHTxxConnectionMock.h"
#include "DHT11.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static bool close_enough(float a, float b, float eps = 0.001f) {
    return std::fabs(a - b) < eps;
}

int main() {
    // --- DHT11Minimal: decoding ---
    {
        DHTxxConnectionMock connection;
        uint8_t frame[5] = {0x35, 0x00, 0x18, 0x04, 0x51};
        connection.queueRead(frame);
        DHT11Minimal<DHTxxConnectionMock> sensor(connection);
        float t, h;
        bool ok = sensor.read(t, h);
        check_true(ok && close_enough(t, 24.4f) && close_enough(h, 53.0f), "decode_datasheet_example");

        uint8_t frame2[5] = {0x20, 0x00, 0x0A, 0x81, 0xAB};
        connection.queueRead(frame2);
        ok = sensor.read(t, h);
        check_true(ok && close_enough(t, -10.1f) && close_enough(h, 32.0f), "decode_negative_temperature");

        // --- Checksum validation ---
        uint8_t badChecksum[5] = {0x35, 0x00, 0x18, 0x04, 0x00};
        connection.queueRead(badChecksum);
        ok = sensor.read(t, h);
        check_true(!ok && !sensor.valid(), "checksum_error_returns_false");

        // Wrong-length frame: skipped intentionally — DHT11Minimal::read()'s
        // out-param frame buffer is a fixed uint8_t[5]; the connection's
        // read(uint8_t*) contract has no way to signal "fewer than 5 bytes
        // were written" other than returning false (a connection-level
        // timeout/framing error), which is already covered by
        // read_retry_recovers_from_transport_failure below.

        // Connection-level failure (timeout/framing error): read() returns
        // false and does not touch temperature/humidity meaningfully.
        connection.queueFailure();
        ok = sensor.read(t, h);
        check_true(!ok, "connection_failure_returns_false");
    }

    // --- DHT11Full: convenience accessors ---
    DHTxxConnectionMock connection2;
    uint8_t frame[5] = {0x35, 0x00, 0x18, 0x04, 0x51};
    connection2.queueRead(frame);
    DHT11Full<DHTxxConnectionMock> full(connection2, 3);
    check_true(close_enough(full.read_temperature(), 24.4f), "read_temperature");

    connection2.queueRead(frame);
    check_true(close_enough(full.read_humidity(), 53.0f), "read_humidity");

    // --- read_raw(): unprocessed frame, still checksum-validated ---
    // This is the check that would have caught a real bug: read_raw() used
    // to just forward the connection's bool result without validating the
    // checksum at all (see DHT11.h's read_raw — now fixed to match
    // read_raw_with_retry()'s existing validation and every other
    // language's read_raw/readRaw, and specs/humidity/dht11.md's read_raw
    // row: "raises on checksum error").
    connection2.queueRead(frame);
    uint8_t raw[5];
    bool ok = full.read_raw(raw);
    check_true(ok && raw[0] == 0x35 && raw[1] == 0x00 && raw[2] == 0x18 && raw[3] == 0x04 && raw[4] == 0x51,
               "read_raw_returns_frame");

    uint8_t badChecksum[5] = {0x35, 0x00, 0x18, 0x04, 0x00};
    connection2.queueRead(badChecksum);
    ok = full.read_raw(raw);
    check_true(!ok, "read_raw_checksum_error_returns_false");

    // read_raw_with_retry(): already validated checksum before this fix;
    // confirm it still does after the read_raw() fix (regression guard).
    connection2.queueRead(badChecksum);
    connection2.queueRead(frame);
    ok = full.read_raw_with_retry(raw);
    check_true(ok && raw[4] == 0x51, "read_raw_with_retry_recovers_from_bad_checksum");

    // --- read_retry(): C++ has no exceptions in this API — both a
    // checksum failure and a connection-level (timeout/framing) failure
    // just make the loop iteration fail to `return true`, so read_retry
    // retries on EITHER kind of failure, same as Go's/Rust's ReadRetry
    // (unlike Python's checksum-only DHT11Error catch scope).
    DHTxxConnectionMock connection3;
    connection3.queueRead(badChecksum);  // bad checksum, attempt 1
    connection3.queueRead(frame);        // good, attempt 2
    DHT11Full<DHTxxConnectionMock> full3(connection3, 3);
    float t, h;
    ok = full3.read_retry(3, t, h);
    check_true(ok && close_enough(t, 24.4f) && close_enough(h, 53.0f), "read_retry_succeeds");

    DHTxxConnectionMock connection4;
    connection4.queueRead(badChecksum);
    connection4.queueRead(badChecksum);
    DHT11Full<DHTxxConnectionMock> full4(connection4, 2);
    ok = full4.read_retry(2, t, h);
    check_true(!ok, "read_retry_exhausted");

    DHTxxConnectionMock connection5;
    connection5.queueFailure();   // transport-level failure, attempt 1
    connection5.queueRead(frame); // would succeed, attempt 2
    DHT11Full<DHTxxConnectionMock> full5(connection5, 3);
    ok = full5.read_retry(3, t, h);
    check_true(ok && close_enough(t, 24.4f) && close_enough(h, 53.0f),
               "read_retry_recovers_from_transport_failure");

    // --- read_retry() with max_retries == 0 falls back to the constructor value ---
    DHTxxConnectionMock connection6;
    connection6.queueRead(badChecksum);
    connection6.queueRead(badChecksum);
    connection6.queueRead(frame);  // 3rd attempt succeeds
    DHT11Full<DHTxxConnectionMock> full6(connection6, 3);
    ok = full6.read_retry(0, t, h);  // 0 -> constructor's default (3)
    check_true(ok && close_enough(t, 24.4f), "read_retry_zero_uses_constructor_default");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
