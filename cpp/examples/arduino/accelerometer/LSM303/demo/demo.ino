#include <Arduino.h>
#include <Wire.h>
#include <Periph.h>
#include <math.h>

#ifndef TEST_SDA
#define TEST_SDA 21
#endif
#ifndef TEST_SCL
#define TEST_SCL 22
#endif

void setup() {
    Serial.begin(115200);
    delay(2000);
#if defined(ARDUINO_ARCH_ESP32)
    Wire.begin(TEST_SDA, TEST_SCL, 400000);
#else
    Wire.begin();                                    // other cores: board's default SDA/SCL
    Wire.setClock(400000);
#endif
    I2CConnection accel_connection(Wire, LSM303Minimal::I2C_ADDRESS_ACCEL);
    I2CConnection mag_connection(Wire, LSM303Minimal::I2C_ADDRESS_MAG);
    LSM303Full accel(accel_connection, mag_connection);  // Create LSM303 driver, (accel_connection, mag_connection)

    // --- Configure the sensor for a responsive compass ---
    // 50 Hz gives a fresh sample every 20 ms without flooding the FIFO, and
    // the high-resolution mode keeps 1 mg/LSB so the tilt compensation stays
    // precise.
    accel.setDataRate(50);                      // Set output data rate, (rate_hz=50) → bool
    accel.setMode(LSM303Full::MODE_HIGH_RESOLUTION);  // Set operating mode, (mode=MODE_HIGH_RESOLUTION=2) → bool
    accel.setMagRange(130);                       // Set magnetometer range, (range_ut=130) → bool
    accel.setMagDataRate(15.0f);                  // Set magnetometer data rate, (rate_hz=15) → bool

    // --- Arm the gestures ---
    // A double tap on the Z axis and a free-fall event both end up on INT1.
    // The latch keeps the line asserted until the source register is read, so
    // a short tap is never missed between two polling rounds.
    accel.setClick(0, 0x04, 0.8f, 20.0f, 40.0f, 200.0f);  // Configure click detector, (single=0, double=0x04, threshold_g=0.8, time_limit_ms=20, latency_ms=40, window_ms=200) → bool
    accel.setFreeFall(1, 0.35f, 60.0f);         // Arm free-fall, (generator=1, threshold_g=0.35, duration_ms=30) → bool
    accel.setLatch(1, true);                    // Latch the interrupt, (generator=1, enabled=true) → bool
    accel.enableInterrupt(LSM303Full::SOURCE_CLICK, 1);  // Route an interrupt, (source=SOURCE_CLICK=0x20, pin=1) → bool
    accel.enableInterrupt(LSM303Full::SOURCE_IA1, 1);  // Route an interrupt, (source=SOURCE_IA1=0x08, pin=1) → bool

    // --- Collect the samples in the FIFO ---
    // Stream mode keeps the newest 32 sample sets; the watermark interrupts as
    // soon as 16 of them have piled up.
    accel.setFifo(LSM303Full::FIFO_STREAM, 16);  // Configure FIFO, (mode=FIFO_STREAM=2, watermark=0, trigger_int2=false) → bool

    float samples[3 * 8];
    for (int round = 0; round < 60; round++) {
        // --- Average the samples the FIFO collected ---
        // Averaging over the whole burst cancels most of the vibration noise a
        // handheld compass would otherwise show.
        float ax = 0, ay = 0, az = 0;
        uint8_t count = accel.readFifo(samples, 8);  // Read FIFO, (out, max_count=8) → byte
        if (count > 0) {
            for (uint8_t i = 0; i < count; i++) {
                ax += samples[i * 3];
                ay += samples[i * 3 + 1];
                az += samples[i * 3 + 2];
            }
            ax /= count;
            ay /= count;
            az /= count;
        } else {
            accel.readAcceleration(ax, ay, az);  // Read acceleration, (x, y, z) → g, g, g
        }

        // --- Compute the heading and the tilt ---
        float heading = accel.heading();         // Read the compass heading, (tilt_compensate=true) → float °
                                                 // tilt-compensated with the accelerometer
        float roll = (float)atan2((double)ay, (double)az) * 180.0 / M_PI;
        float pitch = (float)atan2((double)-ax,
                                    sqrt((double)ay * ay + (double)az * az)) * 180.0 / M_PI;
        float mx, my, mz;
        accel.readMagnetic(mx, my, mz);            // Read magnetic field, (x, y, z) → uT, uT, uT
        float magnitude = (float)sqrt((double)mx * mx + (double)my * my + (double)mz * mz);
        float temperature = accel.readTemperatureDelta();  // Read relative temperature, () → float °C
        static const char* directions[16] = {"N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
                                              "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"};
        int index = (int)((heading + 11.25f) / 22.5f) % 16;
        if (index < 0) index += 16;

        Serial.print("heading "); Serial.print(heading, 1); Serial.print(" deg (");
        Serial.print(directions[index]); Serial.print(")  pitch ");
        Serial.print(pitch, 1); Serial.print(" deg  roll ");
        Serial.print(roll, 1); Serial.print(" deg  field ");
        Serial.print(magnitude, 1); Serial.print(" uT  temp ");
        Serial.print(temperature, 1); Serial.println(" C");

        // --- Report the gestures ---
        uint8_t status = accel.pollInterrupt();  // Read interrupt status, () → byte
                                                 // reading the source registers clears the latched flags
        if (status & LSM303Full::SOURCE_CLICK) {
            Serial.println("DOUBLE TAP");
        }
        if (status & LSM303Full::SOURCE_IA1) {
            Serial.println("FREE FALL");
        }

        delay(1000);
    }
}

void loop() {}
