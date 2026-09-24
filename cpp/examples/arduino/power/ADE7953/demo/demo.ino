#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x38
#endif

#include <Arduino.h>
#include <Wire.h>
#include <Periph.h>

static const float VOLTAGE_GAIN = 251.0f;
static const float CURRENT_GAIN = 30.0f;

void setup() {
    Serial.begin(115200);
    delay(2000);
#if defined(ARDUINO_ARCH_ESP32)
    Wire.begin(TEST_SDA, TEST_SCL, 400000);
#else
    Wire.begin();                                    // other cores: board's default SDA/SCL
    Wire.setClock(400000);
#endif
    I2CConnection conn(Wire, TEST_ADDR);
    ADE7953Full ade(conn, VOLTAGE_GAIN, CURRENT_GAIN);                // Create ADE7953 driver, (connection, voltage_gain=V/V, current_gain=A/V, bus_type='i2c')

    // --- Prepare the chip: enable overcurrent interrupt and pin it to IRQ ---
    // The ADE7953 exposes power-quality events via the IRQ pin. Driving
    // OIA through the chip's own alert output lets the host react without
    // polling every reading every cycle.
    ade.configureOvercurrent(40.0f);                                  // Configure overcurrent, (threshold) → none

    // --- Sample at 1 Hz and emit one structured line per cycle ---
    // The energy accumulator resets on read by default (RSTREAD = 1), so
    // activeEnergy() returns watt-hours accumulated since the previous
    // call. Callers wanting a running total accumulate the returned deltas
    // themselves (or disable read-with-reset and track the 24-bit
    // register's own rollovers instead).
    Serial.println("V\tA\tW\tWh");
    while (true) {
        float v = ade.voltage();                                      // Read bus voltage, () → V
        float i = ade.current();                                      // Read load current, () → A
        float p = ade.activePower();                                  // Read active power, () → W
        float e = ade.activeEnergy();                                 // Read active energy, () → Wh
        Serial.print(v, 2); Serial.print('\t');
        Serial.print(i, 3); Serial.print('\t');
        Serial.print(p, 2); Serial.print('\t');
        Serial.println(e, 5);
        delay(1000);
    }
}

void loop() {}
