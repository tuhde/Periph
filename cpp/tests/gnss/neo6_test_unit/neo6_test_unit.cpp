#include <cstdio>
#include <cmath>
#include <cstring>
#include <string>
#include <vector>
#include <initializer_list>
#include "NEO6ConnectionMock.h"
#include "NEO6.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static bool close_enough(float a, float b, float eps = 0.001f) {
    return std::fabs(a - b) < eps;
}

// Builds a "$<body>*XX\r\n" NMEA sentence with a correct XOR checksum,
// computed here (not by calling into the driver's own checksum logic).
static std::vector<uint8_t> nmeaSentence(const std::string& body) {
    uint8_t checksum = 0;
    for (unsigned char c : body) checksum ^= c;
    char hex[3];
    std::snprintf(hex, sizeof(hex), "%02X", checksum);
    std::string s = "$" + body + "*" + hex + "\r\n";
    return std::vector<uint8_t>(s.begin(), s.end());
}

// Builds a UBX frame with a correct Fletcher checksum, computed here --
// verified independently of NEO6.cpp's own ubxChecksum(), mirroring
// NEO6Full::sendUbx's own framing.
static std::vector<uint8_t> ubxFrame(uint8_t msgClass, uint8_t msgId,
                                      const std::vector<uint8_t>& payload = {}) {
    std::vector<uint8_t> body;
    body.push_back(msgClass);
    body.push_back(msgId);
    uint16_t length = static_cast<uint16_t>(payload.size());
    body.push_back(static_cast<uint8_t>(length & 0xFF));
    body.push_back(static_cast<uint8_t>((length >> 8) & 0xFF));
    body.insert(body.end(), payload.begin(), payload.end());

    uint8_t ckA = 0, ckB = 0;
    for (uint8_t b : body) {
        ckA = static_cast<uint8_t>(ckA + b);
        ckB = static_cast<uint8_t>(ckB + ckA);
    }

    std::vector<uint8_t> frame;
    frame.push_back(0xB5);
    frame.push_back(0x62);
    frame.insert(frame.end(), body.begin(), body.end());
    frame.push_back(ckA);
    frame.push_back(ckB);
    return frame;
}

// Joins fields with ',' rather than a hand-typed, comma-heavy literal, to
// avoid miscounting empty fields.
static std::string joinFields(std::initializer_list<std::string> fields) {
    std::string result;
    bool first = true;
    for (const auto& f : fields) {
        if (!first) result += ',';
        result += f;
        first = false;
    }
    return result;
}

// Queues data and drives update() enough times to consume it all, returning
// true if any call returned true (a GGA fix was parsed).
static bool feed(NEO6Minimal& sensor, NEO6ConnectionMock& connection,
                  const std::vector<uint8_t>& data) {
    connection.queueBytes(data);
    bool gotFix = false;
    for (size_t i = 0; i < data.size(); i++) {
        if (sensor.update()) gotFix = true;
    }
    return gotFix;
}

static bool vecEq(const std::vector<uint8_t>& a, const std::vector<uint8_t>& b) {
    return a == b;
}

int main() {
    const std::string GGA_FIX = joinFields({
        "GPGGA", "092750.000", "5321.6802", "N", "00630.3372", "W",
        "1", "08", "1.03", "61.7", "M", "55.2", "M", "", "",
    });
    const std::string GGA_NO_FIX = joinFields({
        "GPGGA", "092750.000", "", "", "", "",
        "0", "00", "", "", "", "", "", "", "",
    });
    const std::string RMC = joinFields({
        "GPRMC", "092750.000", "A", "5321.6802", "N", "00630.3372", "W",
        "022.4", "084.4", "230394", "003.1", "W", "A",
    });
    const std::string VTG = joinFields({
        "GPVTG", "084.4", "T", "077.4", "M", "022.4", "N", "041.5", "K", "A",
    });

    // --- NEO6Minimal: GGA decode across all three bus types ---
    const NEO6BusType busTypes[] = { NEO6BusType::Uart, NEO6BusType::I2c, NEO6BusType::Spi };
    const char* busNames[] = { "uart", "i2c", "spi" };
    for (int i = 0; i < 3; i++) {
        NEO6ConnectionMock connection;
        NEO6Minimal gps(connection, busTypes[i]);
        char label[64];

        std::snprintf(label, sizeof(label), "init_fix_zero[%s]", busNames[i]);
        check_true(gps.fix() == 0, label);
        std::snprintf(label, sizeof(label), "init_latitude_nan[%s]", busNames[i]);
        check_true(std::isnan(gps.latitude()), label);

        bool gotFix = feed(gps, connection, nmeaSentence(GGA_FIX));
        std::snprintf(label, sizeof(label), "gga_fix_returned_true[%s]", busNames[i]);
        check_true(gotFix, label);
        std::snprintf(label, sizeof(label), "gga_fix_value[%s]", busNames[i]);
        check_true(gps.fix() == 1, label);
        std::snprintf(label, sizeof(label), "gga_satellites[%s]", busNames[i]);
        check_true(gps.satellites() == 8, label);
        std::snprintf(label, sizeof(label), "gga_latitude[%s]", busNames[i]);
        check_true(close_enough(gps.latitude(), 53.361336667f, 1e-6f), label);
        std::snprintf(label, sizeof(label), "gga_longitude[%s]", busNames[i]);
        check_true(close_enough(gps.longitude(), -6.505620f, 1e-6f), label);
        std::snprintf(label, sizeof(label), "gga_altitude[%s]", busNames[i]);
        check_true(close_enough(gps.altitude(), 61.7f), label);
    }

    // --- NEO6Minimal: no-fix GGA updates fix/satellites but not lat/lon ---
    {
        NEO6ConnectionMock connection;
        NEO6Minimal gps(connection);
        feed(gps, connection, nmeaSentence(GGA_FIX));
        bool gotFix = feed(gps, connection, nmeaSentence(GGA_NO_FIX));
        check_true(!gotFix, "gga_no_fix_returns_false");
        check_true(gps.fix() == 0, "gga_no_fix_clears_fix_value");
        check_true(close_enough(gps.latitude(), 53.361336667f, 1e-6f), "gga_no_fix_keeps_last_latitude");
    }

    // --- Checksum validation: a corrupted sentence is silently discarded ---
    {
        NEO6ConnectionMock connection;
        NEO6Minimal gps(connection);
        std::vector<uint8_t> bad = nmeaSentence(GGA_FIX);
        bad[bad.size() - 4] ^= 0xFF;  // corrupt one checksum hex digit
        bool gotFix = feed(gps, connection, bad);
        check_true(!gotFix, "bad_checksum_discarded");
        check_true(gps.fix() == 0, "bad_checksum_leaves_fix_zero");
    }

    // --- Leading 0xFF idle-filler bytes before '$' are ignored ---
    {
        NEO6ConnectionMock connection;
        NEO6Minimal gps(connection);
        std::vector<uint8_t> data = { 0xFF, 0xFF, 0xFF };
        std::vector<uint8_t> sentence = nmeaSentence(GGA_FIX);
        data.insert(data.end(), sentence.begin(), sentence.end());
        bool gotFix = feed(gps, connection, data);
        check_true(gotFix, "leading_garbage_ignored");
    }

    // --- NEO6Full: RMC (speed/course/time/date) and VTG (course/speed) ---
    {
        NEO6ConnectionMock connection;
        NEO6Full gps(connection);
        feed(gps, connection, nmeaSentence(RMC));
        check_true(close_enough(gps.speed(), 22.4f * 0.514444f, 1e-4f), "rmc_speed");
        check_true(close_enough(gps.course(), 84.4f), "rmc_course");
        check_true(std::strcmp(gps.utcTime(), "092750.000") == 0, "rmc_utc_time");
        check_true(std::strcmp(gps.utcDate(), "230394") == 0, "rmc_utc_date");
    }
    {
        NEO6ConnectionMock connection2;
        NEO6Full gps2(connection2);
        feed(gps2, connection2, nmeaSentence(VTG));
        check_true(close_enough(gps2.course(), 84.4f), "vtg_course");
        check_true(close_enough(gps2.speed(), 41.5f / 3.6f, 1e-4f), "vtg_speed");
    }
    {
        NEO6ConnectionMock connection3;
        NEO6Full gps3(connection3);
        feed(gps3, connection3, nmeaSentence(GGA_FIX));
        check_true(close_enough(gps3.hdop(), 1.03f), "gga_hdop");
    }

    // --- NEO6Full: sendUbx / pollUbx / setRate / setPlatform / coldStart / saveConfig ---
    {
        NEO6ConnectionMock connection;
        NEO6Full gps(connection);

        gps.sendUbx(0x06, 0x08, reinterpret_cast<const uint8_t*>("\x01\x02\x03"), 3);
        check_true(vecEq(connection.writes().back(), ubxFrame(0x06, 0x08, {1, 2, 3})),
                   "send_ubx_frames_correctly");

        gps.setRate(5);
        int measRateMs = 1000 / 5;
        std::vector<uint8_t> rateExpected = {
            static_cast<uint8_t>(measRateMs & 0xFF), static_cast<uint8_t>((measRateMs >> 8) & 0xFF),
            1, 0,
            0, 0,
        };
        check_true(vecEq(connection.writes().back(), ubxFrame(0x06, 0x08, rateExpected)),
                   "set_rate_sends_cfg_rate");

        gps.setPlatform(4);
        std::vector<uint8_t> nav5Expected(36, 0);
        nav5Expected[0] = 0x01;  // mask lo
        nav5Expected[1] = 0x00;  // mask hi
        nav5Expected[2] = 4;     // dynModel
        check_true(vecEq(connection.writes().back(), ubxFrame(0x06, 0x24, nav5Expected)),
                   "set_platform_sends_cfg_nav5");

        gps.coldStart();
        std::vector<uint8_t> rstExpected = { 0xFF, 0xFF, 0x02, 0x00 };
        check_true(vecEq(connection.writes().back(), ubxFrame(0x06, 0x04, rstExpected)),
                   "cold_start_sends_cfg_rst");

        gps.saveConfig();
        std::vector<uint8_t> cfgExpected(13, 0);
        cfgExpected[4] = 0xFF; cfgExpected[5] = 0xFF; cfgExpected[6] = 0xFF; cfgExpected[7] = 0xFF;
        cfgExpected[12] = 0x07;
        check_true(vecEq(connection.writes().back(), ubxFrame(0x06, 0x09, cfgExpected)),
                   "save_config_sends_cfg_cfg");
    }

    // --- pollUbx(): queue a matching UBX response frame, expect its payload back ---
    {
        NEO6ConnectionMock connection;
        NEO6Full gps(connection);
        std::vector<uint8_t> responsePayload;
        for (int i = 0; i < 28; i++) responsePayload.push_back(static_cast<uint8_t>(i));
        connection.queueBytes(ubxFrame(0x01, 0x02, responsePayload));

        uint8_t outPayload[64];
        size_t outLen = 0;
        bool ok = gps.pollUbx(0x01, 0x02, outPayload, outLen, sizeof(outPayload));
        std::vector<uint8_t> got(outPayload, outPayload + outLen);
        check_true(ok && vecEq(got, responsePayload), "poll_ubx_returns_payload");
        check_true(vecEq(connection.writes()[0], ubxFrame(0x01, 0x02)), "poll_ubx_sends_poll_frame");
    }

    // --- pollUbx() returns false on ACK-NAK ---
    {
        NEO6ConnectionMock connection;
        NEO6Full gps(connection);
        connection.queueBytes(ubxFrame(0x05, 0x00, {0x06, 0x08}));  // ACK-NAK for CFG-RATE
        uint8_t outPayload[64];
        size_t outLen = 0;
        bool ok = gps.pollUbx(0x06, 0x08, outPayload, outLen, sizeof(outPayload));
        check_true(!ok, "poll_ubx_nak_returns_false");
    }

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
