#include <Wire.h>
#include <stdarg.h>
#include <stdio.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x60);
Si1145Full sensor(connection);                                     // Create Si1145 driver, (connection) → void

static void periph_printf(const char* fmt, ...) {
    char buf[96];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    Serial.print(buf);
}

static unsigned uv_whole(float uv) { return (unsigned)(uv * 100.0f + 0.5f) / 100; }
static unsigned uv_frac(float uv) { return (unsigned)(uv * 100.0f + 0.5f) % 100; }

static volatile uint8_t g_events = 0;
static volatile uint8_t g_last = 0;
static void on_event(uint8_t status) { g_events = (uint8_t)(g_events + 1); g_last = status; }

void setup() {
    Serial.begin(115200);
    Wire.begin();
    // The Si1145 has the fixed address 0x60 (older silicon: 0x5A); no bus traffic yet.
    sensor.init();                                                 // Check PART_ID, HW_KEY, UV + ALS + PS1, () → bool
    // Waits 25 ms, verifies PART_ID 0x45/0x46/0x47, writes HW_KEY 0x17, UCOEF, CHLIST 0xB1, LED1 level 3.

    uint8_t part = 0, rev = 0, seq = 0;
    sensor.read_id(part, rev, seq);                                // Identity registers, (part_id&, rev_id&, seq_id&) → bool
    // PART_ID 0x45 for the Si1145; SEQ_ID 0x01 parts swap the MEAS_RATE bytes.
    periph_printf("PART_ID 0x%02X REV_ID 0x%02X SEQ_ID 0x%02X\n", (unsigned)part, (unsigned)rev, (unsigned)seq);
    uint8_t counter = 0, err = 0;
    sensor.read_response(counter, err);                            // RESPONSE decoded, (counter&, error&) → bool
    // 4-bit command counter and the error code (0 = none, 0x8x = ADC overflow / invalid setting).
    periph_printf("counter %u error 0x%02X\n", (unsigned)counter, (unsigned)err);

    Si1145Reading r;
    sensor.read(r);                                                // Forced PS + ALS, (reading&) → bool
    // PSALS_FORCE, waits for ALS_INT and PS1_INT; raw counts and the UV index (AUX_DATA / 100).
    periph_printf("prox %u UV %u.%02u vis %u ir %u\n", (unsigned)r.proximity, uv_whole(r.uv_index),
          uv_frac(r.uv_index), (unsigned)r.visible, (unsigned)r.infrared);
    uint16_t v = 0;
    float uv = 0.0f;
    sensor.read_proximity(v, 1);                                   // Forced PS, (value&, channel=1) → bool
    // PS1 only; PS2/PS3 exist on the Si1146/47.
    periph_printf("proximity %u\n", (unsigned)v);
    sensor.read_uv_index(uv);                                      // Forced ALS, (value&) → bool
    // UV index with the default UV coefficients 7B 6B 01 00.
    periph_printf("UV %u.%02u\n", uv_whole(uv), uv_frac(uv));
    sensor.read_visible(v);                                        // Forced ALS, (value&) → bool
    // Raw visible ambient light.
    periph_printf("visible %u\n", (unsigned)v);
    sensor.read_infrared(v);                                       // Forced ALS, (value&) → bool
    // Raw infrared ambient light.
    periph_printf("infrared %u\n", (unsigned)v);
    Si1145Raw raw;
    sensor.read_raw(raw);                                          // Data registers, (raw&) → bool
    // No new measurement; the latest values of 0x22–0x2D.
    periph_printf("raw vis %u ir %u ps1 %u aux %u\n", (unsigned)raw.visible, (unsigned)raw.infrared,
          (unsigned)raw.ps1, (unsigned)raw.aux);
    sensor.read_temperature_raw(v);                                // AUX temperature, (value&) → bool
    // Relative die temperature; CHLIST is switched to EN_AUX and restored.
    periph_printf("temperature raw %u\n", (unsigned)v);
    sensor.read_vdd_raw(v);                                        // AUX VDD, (value&) → bool
    // Supply voltage on the auxiliary channel.
    periph_printf("VDD raw %u\n", (unsigned)v);

    sensor.set_channels(true, true, true, true);                   // CHLIST, (uv, visible, infrared, ps1, ps2=false, ps3=false, aux=false) → bool
    // UV and AUX share AUX_DATA, so only one of them can be enabled.
    sensor.set_led_current(1, 8);                                  // LED drive, (led, level) → bool
    // Level 0 = off … 15 = maximum (~359 mA); keep the gain low at high currents.
    sensor.set_ps_led(1, 0x01);                                    // PS LED select, (channel, leds) → bool
    // PS1 drives LED1; the Si1145 has no LED2/LED3.
    sensor.set_ps_photodiode(1, true);                             // PS photodiode, (channel, large) → bool
    // Large IR photodiode (0x03); false selects the small one.
    sensor.set_gain(0, 0, 0);                                      // ADC gains, (visible=0, infrared=0, proximity=0) → bool
    // Integration time × 2^n, recovery counts at the one's complement of the gain.
    sensor.set_high_range(false, false, false);                    // Signal range, (visible, infrared, proximity) → bool
    // true divides the sensitivity by 14.5 (direct sunlight).
    sensor.set_alignment();                                        // Result alignment, (visible, infrared, ps1, ps2, ps3) → bool
    // true reports the 16 LSBs instead of the 16 MSBs of the ADC result.
    const uint8_t ucoef[4] = {0x7B, 0x6B, 0x01, 0x00};
    sensor.set_uv_coefficients(ucoef);                             // UCOEF0..3, (ucoef[4]) → bool
    // 7B 6B 01 00 make AUX_DATA the UV index × 100.
    uint8_t cal[12];
    sensor.read_calibration(cal);                                  // GET_CAL, (cal[12]) → bool
    // Factory calibration bytes; overwrites the data registers.
    periph_printf("calibration %02X %02X ... %02X\n", (unsigned)cal[0], (unsigned)cal[1], (unsigned)cal[11]);

    sensor.poll_interrupt();                                       // IRQ_STATUS, () → uint8_t sources
    // Reads and clears pending flags.
    sensor.start_autonomous(0.1f);                                 // Autonomous mode, (interval_s, proximity=true, als=true) → bool
    // MEAS_RATE = 0.1 s / 31.25 µs = 3200, then PSALS_AUTO.
    delay(500);
    periph_printf("sources 0x%02X\n", (unsigned)sensor.poll_interrupt()); // IRQ_STATUS, () → uint8_t sources
    // SOURCE_ALS | SOURCE_PS1 when both finished at least once.
    sensor.pause();                                                // PSALS_PAUSE, () → bool
    // Stops the autonomous loop.
    sensor.force(true, false);                                     // PS_FORCE without waiting, (proximity=true, als=true) → bool
    // The result arrives when IRQ_STATUS flags PS1.
    delay(50);
    sensor.read_raw(raw);
    periph_printf("ps1 %u\n", (unsigned)raw.ps1);

    sensor.enable_interrupt(Si1145Full::SOURCE_ALS | Si1145Full::SOURCE_PS1); // INT_CFG + IRQ_ENABLE, (source) → bool
    // INT pin (active low) driven for ALS and PS1 completions.
    sensor.on_interrupt(on_event);                                 // Subscribe, (callback, intPin=nullptr) → void
    // INT falling edge via the connection's pin; without a pin, poll with poll_interrupt().
    sensor.force();                                                // PSALS_FORCE without waiting, (proximity=true, als=true) → bool
    delay(200);
    sensor.off_interrupt();                                        // Unsubscribe, () → void
    periph_printf("%u events, last 0x%02X\n", (unsigned)g_events, (unsigned)g_last);
    sensor.disable_interrupt(Si1145Full::SOURCE_CMD);              // IRQ_ENABLE clear, (source) → bool
    // CMD_INT is not used by default.

    sensor.set_i2c_address(0x60);                                  // I2C_ADDR + BUSADDR, (address) → bool
    // Volatile; here the address stays 0x60, otherwise reconnect on the new address.
    sensor.reset();                                                // RESET + HW_KEY + defaults, () → bool
    // Software reset, 10 ms wait, then the Minimal configuration again.
}

void loop() {
}
