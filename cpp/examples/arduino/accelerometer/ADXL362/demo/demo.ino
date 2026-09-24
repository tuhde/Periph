#include <SPI.h>
#include <Periph.h>

#ifndef TEST_CS_PIN
#define TEST_CS_PIN 10
#endif

SPISettings settings(8000000, MSBFIRST, SPI_MODE0);
SPIConnection connection(SPI, TEST_CS_PIN, settings);                   // Create SPI connection, (SPI, cs_pin=10, settings) → SPIConnection
ADXL362Full accel(connection);                                           // Create ADXL362 Full driver, (connection) → ADXL362Full

// --- Watch for asleep<->awake transitions and time them ---
// Loop-mode chip autonomously toggles between activity/inactivity
// detection; we only read awake() periodically (cheap STATUS poll) and
// mark transitions with a millis() stamp. Counts are reported every
// 5 s, alongside the elapsed wall-clock.
static uint32_t last_state_ms = 0;
static int      last_awake    = -1;  // unknown
static int      transitions   = 0;
static uint32_t last_report_ms = 0;
static uint32_t start_ms       = 0;

void setup() {
    Serial.begin(115200);
    SPI.begin();
    delay(2000);

    // --- Configure referenced activity/inactivity thresholds ---
    // 0.25 g activity threshold and 0.15 g inactivity threshold (both
    // relative to orientation at engagement) — picked-up or tapped motion
    // easily exceeds 0.25 g, while a stationary board settles below 0.15 g.
    accel.set_activity_threshold(0.25f, true);                           // Set activity threshold, (threshold_g=0.25, referenced=true) → None
    accel.set_inactivity_threshold(0.15f, true);                         // Set inactivity threshold, (threshold_g=0.15, referenced=true) → None
    accel.set_inactivity_time(30);                                       // Set inactivity time, (samples=30) → None
                                                                        // ~5 s at wake-up mode's ~6 Hz sample rate before INACT fires

    // --- Engage linked/loop mode and enable both detectors ---
    // Both ACT_EN and INACT_EN must be 1 to engage loop mode.
    accel.enable_activity_detection(true);                               // Enable activity detection, (enabled=true) → None
    accel.enable_inactivity_detection(true);                             // Enable inactivity detection, (enabled=true) → None
    accel.set_link_loop_mode(ADXL362Full::LINKLOOP_LOOP);                // Set link/loop mode, (mode=LOOP=3) → None
                                                                        // chip autonomously toggles ACT/INACT without host servicing

    // --- Map AWAKE to INT2 and enter wake-up mode ---
    accel.set_interrupt(2, ADXL362Full::SOURCE_AWAKE, true);             // Map AWAKE to INT2, (pin=2, source=AWAKE=6, enabled=true) → None
    accel.set_wakeup_mode(true);                                         // Enter wake-up mode, (enabled=true) → None
                                                                        // POWER_CTL.WAKEUP=1 — ~270 nA idle, ~6 Hz sampling, single-sample activity only

    Serial.println("Watching for motion. Pick up or tap the board to wake; "
                   "let it settle to sleep.");
    start_ms      = millis();
    last_report_ms = start_ms;
}

void loop() {
    bool now_awake = accel.awake();                                      // Read AWAKE bit, () → bool
    if (last_awake == -1 || (int)now_awake != last_awake) {
        Serial.print(last_state_ms = millis() - start_ms);
        Serial.print(" ms  ");
        Serial.println(now_awake ? "AWAKE" : "asleep");                  // Print timestamped state, () → None
        last_awake = (int)now_awake;
        transitions++;
    }

    if (millis() - last_report_ms >= 5000) {                              // Loop until 5 s elapsed, () → bool
        Serial.print("transitions so far: ");
        Serial.println(transitions);                                     // Print transitions so far, () → None
        last_report_ms = millis();
    }

    delay(200);                                                          // Sleep 200 ms between polls, () → None
}
