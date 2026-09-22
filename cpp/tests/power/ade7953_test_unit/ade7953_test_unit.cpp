#include <cstdio>
#include <cstdint>
#include <cmath>
#include "I2CConnectionMock.h"
#include "ADE7953.h"

static const float VOLTAGE_GAIN = 100.0f;
static const float CURRENT_GAIN = 10.0f;

static int passed = 0;
static int failed = 0;

static void check_eq(const char* label, float got, float expected) {
    if (std::fabs(got - expected) < 1e-3f) {
        std::printf("PASS %s\n", label); passed++;
    } else {
        std::printf("FAIL %s: got %.6f, expected %.6f\n", label, got, expected);
        failed++;
    }
}

int main() {
    I2CConnectionMock conn;
    conn.setAddressWidth(2);   // ADE7953 registers are 16-bit addressed
    conn.setRegister(0x21C, {0x89, 0xD1, 0x47});   // VRMS = 0x89D147 = 9032007

    ADE7953Full ade(conn, VOLTAGE_GAIN, CURRENT_GAIN);

    // --- voltage ---
    float v_expected = 100.0f * (0.3535533905932738f / 9032007.0f) * 9032007.0f;
    check_eq("voltage full-scale", ade.voltage(), v_expected);

    // --- current Channel A ---
    conn.setRegister(0x21A, {0x89, 0xD1, 0x47});   // IRMSA = 9032007
    float i_expected = 10.0f * (0.3535533905932738f / 9032007.0f) * 9032007.0f;
    check_eq("current_a full-scale", ade.current(), i_expected);

    // --- activePower ---
    conn.setRegister(0x212, {0x4A, 0x31, 0xC1});   // AWATT = 0x4A31C1 = 4862401
    float p_expected = (4862401.0f * 0.125f * 100.0f * 10.0f) / 4862401.0f;
    check_eq("activePower full-scale", ade.activePower(), p_expected);

    // --- reset() writes SWRST in CONFIG ---
    int writes_before = (int)conn.writes().size();
    ade.reset();
    int writes_after = (int)conn.writes().size();
    // The first new write is a read of CONFIG (write_read), the second
    // is the write of CONFIG with SWRST (bit 7) set in the LSB byte.
    // CONFIG is 0x102; the write carries [addrMSB, addrLSB, byte_HI, byte_LO].
    bool found_swrst = false;
    for (int i = writes_before + 1; i < writes_after; i++) {
        const auto& w = conn.writes()[i];
        if (w.size() >= 4 && w[0] == 0x01 && w[1] == 0x02 && (w[3] & 0x80) != 0) {
            found_swrst = true;
            break;
        }
    }
    if (found_swrst) { std::printf("PASS reset writes SWRST\n"); passed++; }
    else             { std::printf("FAIL reset writes SWRST\n"); failed++; }

    std::printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}