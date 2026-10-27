#include <Wire.h>
#include <math.h>
#include <Periph.h>

const char* const COMPASS_16[16] = {
    "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
    "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW",
};

I2CConnection accelConnection(Wire, 0x19);
I2CConnection magConnection(Wire, 0x1E);
LSM303Full sensor(accelConnection, magConnection);       // Create LSM303 Full driver, (accelConnection, magConnection)

// --- Tilt-compensated compass with tap and free-fall detection ---
// ±2 g and 50 Hz in high-resolution; the FIFO smooths the tilt angles.
// Double-tap on Z and a latched 60 ms free-fall print one-line events;
// every second the FIFO is drained and averaged into a compass update.
void setup() {
    Serial.begin(115200);
    Wire.begin();

    sensor.set_range(2);                                 // Set accelerometer range, (range_g=2) → None
    sensor.set_data_rate(50);                            // Set accelerometer data rate, (rate_hz=50) → None
    sensor.set_mode(2);                                  // Set operating mode, (mode=2 high-resolution) → None
    sensor.set_mag_range(130);                           // Set magnetometer range, (range_ut=130) → None
    sensor.set_mag_data_rate(15);                        // Set magnetometer data rate, (rate_hz=15) → None

    sensor.set_click(0, 4, 0.8, 20, 40, 200);            // Configure click detection, (single=0, double=4, threshold_g=0.8, time_limit_ms=20, latency_ms=40, window_ms=200) → None
                                                         // double-tap on Z (axis mask Z=4), at ±2 g
    sensor.set_free_fall(1, 0.35, 60);                   // Configure free-fall, (generator=1, threshold_g=0.35, duration_ms=60) → None
    sensor.set_latch(1, true);                           // Set latch, (generator=1, enabled=true) → None
                                                         // latch free-fall until read_interrupt_source(1)
    sensor.enable_interrupt(LSM303Full::SOURCE_CLICK, 1);// Route interrupt, (source=CLICK, pin=1) → None
    sensor.enable_interrupt(LSM303Full::SOURCE_IA1, 1);  // Route interrupt, (source=IA1, pin=1) → None

    sensor.set_fifo(2, 16);                              // Configure FIFO, (mode=2 stream, watermark=16) → None
                                                         // stream mode keeps the newest samples for the average
}

void loop() {
    // --- Drain and average the FIFO, then update the compass ---
    float fx[32], fy[32], fz[32];
    uint8_t n = sensor.read_fifo(fx, fy, fz, 32);        // Drain FIFO, (x, y, z, max_samples=32) → floats³, count
                                                         // pops all unread sample sets (up to 32)
    float axv = 0, ayv = 0, azv = 0;
    for (uint8_t i = 0; i < n; i++) {
        axv += fx[i]; ayv += fy[i]; azv += fz[i];
    }
    if (n > 0) { axv /= n; ayv /= n; azv /= n; }

    float mx, my, mz;
    sensor.read_magnetic(mx, my, mz);                    // Read 3-axis magnetic field, (x, y, z) → µT, µT, µT
    float hdg = sensor.heading(true);                    // Compute compass heading, (tilt_compensate=true) → degrees
    float temp = sensor.read_temperature_delta();        // Read relative temperature, () → °C

    float pitch = atan2f(-axv, sqrtf(ayv * ayv + azv * azv)) * 180.0f / 3.14159265f;
    float roll  = atan2f(ayv, azv) * 180.0f / 3.14159265f;
    float bmag  = sqrtf(mx * mx + my * my + mz * mz);
    int dir = ((int)((hdg + 11.25f) / 22.5f)) % 16;
    if (dir < 0) dir += 16;

    Serial.print("hdg="); Serial.print(hdg, 1); Serial.print(" ");
    Serial.print(COMPASS_16[dir]); Serial.print(" pitch="); Serial.print(pitch, 1);
    Serial.print(" roll="); Serial.print(roll, 1);
    Serial.print(" |B|="); Serial.print(bmag, 1);
    Serial.print(" T="); Serial.print(temp, 1);
    Serial.print(" fifo="); Serial.println(n);

    uint16_t status = sensor.poll_interrupt();          // Poll all sources, () → status mask
                                                        // source reads clear the latched flags
    if (status & LSM303Full::SOURCE_IA1) {
        (void)sensor.read_interrupt_source(1);          // Read interrupt source, (generator=1) → source byte
                                                        // clears the latched free-fall interrupt
        Serial.println("FREE FALL");
    }
    if (status & LSM303Full::SOURCE_CLICK) {
        (void)sensor.read_click_source();               // Read click source, () → source byte
                                                        // clears the latched double-tap interrupt
        Serial.println("DOUBLE TAP");
    }
    delay(1000);
}
