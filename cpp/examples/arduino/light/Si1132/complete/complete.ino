#include <Wire.h>
#include <Periph.h>
#include <stdio.h>

I2CConnection connection(Wire, Si1132Full::I2C_ADDRESS);
Si1132Full sensor(connection);                                  // Create Si1132 driver, (connection)

void setup() {
    Wire.begin();
    Serial.begin(115200);
    sensor.init();                                                  // Initialize the chip, () → bool
                                                                    // checks PART_ID, writes HW_KEY, UV coefficients and CHLIST
    float uv = 0; uint16_t vis = 0, ir = 0, aux = 0;
    sensor.read(uv, vis, ir);                                       // Read UV index and ALS counts, (uv&, visible&, infrared&) → bool
    Serial.print("UV "); Serial.print(uv, 2); Serial.print(" visible "); Serial.print((unsigned long)(vis)); Serial.print(" infrared "); Serial.print((unsigned long)(ir)); Serial.println();
    Serial.print("uv index "); Serial.print(sensor.read_uv_index(), 2); Serial.println();// Read the UV index, () → float
                                                                    // forced measurement, AUX_DATA / 100
    sensor.read_visible(vis);                                       // Read visible counts, (visible&) → bool
    sensor.read_infrared(ir);                                       // Read infrared counts, (infrared&) → bool
    sensor.read_raw(vis, ir, aux);                                  // Read the data registers, (visible&, infrared&, aux&) → bool
                                                                    // latest values, no measurement is started
    Serial.print("raw "); Serial.print((unsigned long)(vis)); Serial.print(" "); Serial.print((unsigned long)(ir)); Serial.print(" "); Serial.print((unsigned long)(aux)); Serial.println();
    sensor.read_temperature_raw(aux);                               // Read die temperature, (raw&) → bool
                                                                    // auxiliary channel, relative only
    Serial.print("temperature raw "); Serial.print((unsigned long)(aux)); Serial.println();
    sensor.read_vdd_raw(aux);                                       // Read supply voltage, (raw&) → bool
                                                                    // auxiliary channel with AUX_ADCMUX = 0x75
    Serial.print("vdd raw "); Serial.print((unsigned long)(aux)); Serial.println();
    bool ok = sensor.set_channels(true, true, true);                // Select channels, (uv, visible, infrared, aux=false) → bool
                                                                    // uv and aux cannot be combined
    ok = sensor.set_gain(2, 2);                                     // Set the ADC gain, (visible 0-7, infrared 0-7) → bool
                                                                    // integration time x 2^n plus recovery count
    ok = sensor.set_gain(0, 0);
    ok = sensor.set_high_range(false, false);                       // Set the high signal range, (visible, infrared) → bool
                                                                    // true divides the sensitivity by 14.5
    ok = sensor.set_alignment(true, true);                          // Set the data alignment, (visible_low, infrared_low) → bool
                                                                    // true reports the least significant 16 bits
    const uint8_t ucoef[4] = {0x7B, 0x6B, 0x01, 0x00};
    ok = sensor.set_uv_coefficients(ucoef);                         // Set the UV coefficients, (ucoef[4]) → bool
                                                                    // datasheet defaults for an open-air UV index
    uint8_t cal[12];
    ok = sensor.read_calibration(cal);                              // Read the factory calibration, (out[12]) → bool
                                                                    // GET_CAL then registers 0x22-0x2D
    ok = sensor.force();                                            // Start a forced measurement, () → bool
                                                                    // ALS_FORCE without waiting
    delay(50);
    Serial.print("ready "); Serial.print((unsigned long)(sensor.measurement_ready())); Serial.println();// Check measurement done, () → bool
                                                                    // IRQ_STATUS.ALS_INT
    uint8_t counter = 0, error = 0;
    sensor.read_response(counter, error);                           // Read the command response, (counter&, error&) → bool
                                                                    // error 0 = none
    Serial.print("response "); Serial.print((unsigned long)(counter)); Serial.print(" error "); Serial.print((unsigned long)(error)); Serial.println();
    Serial.print("poll 0x"); Serial.print((unsigned)(sensor.poll_interrupt()), HEX); Serial.println();// Poll interrupt status, () → uint8_t
                                                                    // reads IRQ_STATUS and clears it
    ok = sensor.start_autonomous(0.5f);                             // Start autonomous mode, (interval_s 0-2.05) → bool
                                                                    // MEAS_RATE in 31.25 us steps then ALS_AUTO
    ok = sensor.enable_interrupt();                                 // Enable the INT pin, () → bool
                                                                    // INT_OE and ALS_IE
    delay(1000);
    ok = sensor.disable_interrupt();                                // Disable the INT pin, () → bool
    ok = sensor.pause();                                            // Pause autonomous mode, () → bool
                                                                    // ALS_PAUSE
    ok = sensor.set_i2c_address(0x60);                              // Set the I2C address, (address 7-bit) → bool
                                                                    // I2C_ADDR parameter then BUSADDR, volatile
    ok = sensor.reset();                                            // Reset the chip, () → bool
                                                                    // RESET, waits, writes HW_KEY again
    Serial.print("ok "); Serial.print((unsigned long)(ok)); Serial.println();
}

void loop() {
}
