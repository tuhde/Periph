#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "Pca9685.h"
#include <math.h>

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x40;
    I2CConnectionLinux connection(bus, addr);

    Pca9685Full pwm(connection);                      // Create PCA9685 driver, (connection)

    // --- LED fader and servo sweep ---
    // Fade channel 0 at 1 kHz, show a phase-shifted pair, run a 16-channel chase,
    // switch to 50 Hz and sweep a servo on channel 15 from 1 ms to 2 ms, then show
    // all-channel writes, full ON/OFF, output modes, sleep/wake/restart with the
    // outputs preserved, the all-call and sub addresses, and finish with a reset.
    pwm.set_frequency(1000.0f);                           // 1 kHz PWM for LEDs, (hz) → bool
    for (int i = 0; i <= 100; i++) {
        pwm.set_duty(0, 0.5f - 0.5f * cosf(3.14159265f * i / 50.0f));// Fade up and down, (channel, duty) → bool
        usleep((20) * 1000);
    }
    pwm.set_pwm(1, 0, 1024);                              // 25 % duty, no delay, (channel, on, off) → bool
    pwm.set_pwm(2, 2048, 3072);                           // Same duty, shifted by half a period
    uint16_t on = 0, off = 0;
    pwm.get_pwm(2, on, off);                              // Read back the counts, (channel, &on, &off) → bool
    printf("%d\n", (int)(on));
    printf("%d\n", (int)(off));
    usleep((1000) * 1000);
    for (uint8_t ch = 0; ch < 16; ch++) {
        pwm.set_duty(ch, 1.0f);                           // Chase across all channels, (channel, duty) → bool
        usleep((50) * 1000);
        pwm.set_duty(ch, 0.0f);
    }
    pwm.set_all_duty(0.2f);                               // All channels together, (duty) → bool
    usleep((500) * 1000);
    pwm.set_all_pwm(0, 3072);                             // 75 % on all channels, (on, off) → bool
    usleep((500) * 1000);
    pwm.set_all_duty(0.0f);
    pwm.set_full_on(3);                                   // Constant high, (channel) → bool
    usleep((500) * 1000);
    pwm.set_full_off(3);                                  // Constant low, (channel) → bool
    pwm.set_frequency(50.0f);                             // Servo frequency, (hz) → bool
    for (int us = 1000; us <= 2000; us += 50) {
        pwm.set_pulse_width(15, us / 1e6f);               // Servo pulse 1–2 ms, (channel, seconds) → bool
        usleep((50) * 1000);
    }
    printf("%d\n", (int)(pwm.get_frequency()));           // () → float Hz
    pwm.set_frequency(1000.0f);
    pwm.set_duty(4, 0.5f);
    pwm.set_output_mode(false, true, Pca9685Full::OFF_LOW);// Inverted output, (open_drain, invert, off_state) → bool
    usleep((500) * 1000);
    pwm.set_output_mode(true, false, Pca9685Full::OFF_HIGHZ);// Open-drain, high-Z when OE = 1
    usleep((500) * 1000);
    pwm.set_output_mode();                                // Back to totem-pole, not inverted
    pwm.set_update_on_ack(true);                          // Update on ACK, (enabled) → void
    pwm.set_update_on_ack(false);
    pwm.sleep();                                          // Outputs pause, PWM registers kept, () → void
    usleep((500) * 1000);
    pwm.wake();                                           // Outputs resume, () → void
    pwm.restart();                                        // Restart sequence, () → void
    pwm.set_allcall(true);                                // Respond to all-call, (enabled, address) → bool
    pwm.set_allcall(false);
    pwm.set_subaddress(1, 0x71, true);                    // Respond to sub address 1, (index, address, enabled) → bool
    pwm.set_subaddress(1, 0x71, false);
    uint8_t mode1 = 0, mode2 = 0;
    pwm.read_mode(mode1, mode2);                          // () → (mode1, mode2)
    printf("%d\n", (int)(mode1));
    printf("%d\n", (int)(mode2));
    pwm.reset();                                          // Back to power-on state, () → void

    return 0;
}
