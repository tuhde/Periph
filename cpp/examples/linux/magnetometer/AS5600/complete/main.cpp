#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "AS5600.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x36;
    I2CConnectionLinux connection(bus, addr);

    AS5600Full as(connection);                                              // Create AS5600 driver, (connection)

    printf("magnet detected=%d too_strong=%d too_weak=%d\n",
           as.is_magnet_detected(), as.is_magnet_too_strong(),             // Magnet status, () → bool
           as.is_magnet_too_weak());
    printf("status=0x%02X agc=%u magnitude=%u\n", as.status_byte(),        // Raw STATUS byte, () → uint8_t (MH/ML/MD bits)
           as.agc(), as.magnitude());                                      // AGC gain 0–255, () → uint8_t ; CORDIC magnitude, () → uint16_t
    printf("angle=%.2f deg raw_angle=%u (%.2f deg) angle_raw=%u\n",
           (double)as.angle(), as.raw_angle(),                             // Scaled angle, () → float ° ; unscaled count, () → uint16_t 0–4095
           (double)as.raw_angle_degrees(), as.angle_raw());                // Unscaled angle, () → float ° ; scaled count, () → uint16_t

    as.set_zero_position(as.raw_angle());                                  // Set ZPOS, (pos 0–4095) → void
                                                                           // the current position becomes 0°
    as.set_max_position((as.zero_position() + 2048) & 0x0FFF);            // Set MPOS, (pos 0–4095) → void
                                                                           // 180° span; either MPOS or MANG defines the range
    as.set_max_angle(2048);                                                // Set MANG span, (span 0–4095) → void
    printf("zpos=%u mpos=%u mang=%u\n", as.zero_position(),               // Read ZPOS/MPOS/MANG, () → uint16_t
           as.max_position(), as.max_angle());

    as.configure(AS5600Full::PM_NOM, 1, AS5600Full::OUTS_PWM, 3, 0, 0, false);  // Write CONF, (pm, hyst, outs, pwmf, sf, fth, wd) → void
                                                                           // PWM output at 920 Hz, 1 LSB hysteresis
    printf("burn_count=%u\n", as.burn_count());                           // OTP burns used, () → uint8_t 0–3

    // burn_angle()/burn_setting() program one-time-programmable memory and
    // cannot be undone, so they only run when explicitly requested.
    const char* burn = getenv("AS5600_BURN");
    if (burn && burn[0] == '1') {
        as.burn_angle();                                                   // Permanently store ZPOS/MPOS, () → void
        as.burn_setting();                                                 // Permanently store MANG/CONF, () → void
    }
    return 0;
}
