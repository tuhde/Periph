#include <stdarg.h>
#include <Wire.h>
#include <Periph.h>

static void logPrintf(const char* fmt, ...) {
    char buf[128];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);
    Serial.print(buf);
}

// Shunt-current monitor: a 0.1 ohm shunt in the load supply line is read on
// the ±0.256 V range; the traditional comparator watches for more than 1 A.

static const float SHUNT_OHMS = 0.1f;
static const int SAMPLES = 20;

void setup() {
    Serial.begin(115200);
    Wire.begin();
    I2CConnection connection(Wire, ADS1014Minimal::I2C_ADDRESS);
    ADS1014Full adc(connection);                        // Create ADS1014 Full driver, (connection)

    // --- Pick the range and speed for a small shunt voltage ---
    // ±0.256 V gives 0.125 mV per code; 250 SPS keeps noise low and is plenty
    // for a once-per-second report.
    adc.setGain(ADS1014Full::PGA_0_256);                // Set PGA, (pga 0–5) → bool
    adc.setDataRate(ADS1014Full::DR_250);               // Set data rate, (dr 0–6) → bool

    // --- Arm the over-current comparator ---
    // 0.1 V across 0.1 ohm is 1 A. Traditional comparator with hysteresis
    // (clears again below 0.08 V), latched so a brief spike is not missed,
    // and 4 consecutive conversions are needed to assert.
    adc.setThresholds(0.08f, 0.1f);                     // Set both thresholds, (low V, high V) → bool
    adc.configureComparator(ADS1014Full::COMP_TRADITIONAL, ADS1014Full::ACTIVE_LOW, true, ADS1014Full::QUEUE_4);  // Configure comparator, (mode, polarity, latch, queue) → bool
    adc.enableInterrupt(ADS1014Full::SOURCE_COMPARATOR);  // Route comparator to ALERT/RDY, (source) → bool

    // --- Monitor the current ---
    // The pin level is not wired in this demo, so the alert state is inferred
    // from the measured current; clearAlert() releases the latch afterwards.
    for (int i = 0; i < SAMPLES; i++) {
        float v = adc.readVoltage();                    // Read voltage, () → V
        float amps = v / SHUNT_OHMS;
        logPrintf("%.3f V -> %.2f A\n", (double)v, (double)amps);
        if (amps > 1.0f) {
            logPrintf("over-current!\n");
            adc.clearAlert();                           // Release latched alert, () → void
        }
        delay(1000);
    }

    // --- Release the alert pin when finished ---
    adc.disableInterrupt(ADS1014Full::SOURCE_COMPARATOR);  // Release ALERT/RDY, (source) → void
}

void loop() {}
