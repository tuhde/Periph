#include <Wire.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x55);
Si1133Full sensor(connection);                                     // Create Si1133 driver, (connection) → void

static void on_event(uint8_t status) {
    Serial.print("interrupt 0x"); Serial.print((unsigned)(status), HEX); Serial.println();
}

void setup() {
    Serial.begin(115200);
    Wire.begin();
    sensor.init();                                                 // Check PART_ID, reset, UV + white channels, () → bool

    // --- Identity ---
    // PART_ID 0x33 identifies the Si1133; HW_ID and REV_ID give silicon and
    // product revision. RESPONSE0 shows the sequencer state and command counter.
    uint8_t part = 0, hw = 0, rev = 0, state = 0, counter = 0;
    bool err = false;
    sensor.read_id(part, hw, rev);                                 // Identity registers, (part_id&, hw_id&, rev_id&) → bool
    sensor.read_response(state, err, counter);                     // RESPONSE0 decoded, (state&, error&, counter&) → bool
    Serial.print("PART_ID 0x"); Serial.print((unsigned)(part), HEX); Serial.print(" HW_ID 0x"); Serial.print((unsigned)(hw), HEX); Serial.print(" REV_ID 0x"); Serial.print((unsigned)(rev), HEX); Serial.print(" state 0x"); Serial.print((unsigned)(state), HEX); Serial.print(" counter "); Serial.print((unsigned)(counter)); Serial.println();

    // --- UV and daylight monitor: 20 s at 1 Hz ---
    // The counts are raw ADC values; a UV index needs Silicon Labs' calibration
    // for the product's window, so watch the trend.
    int32_t uv = 0, white = 0;
    for (int i = 0; i < 20; i++) {
        sensor.read(uv, white);                                    // Forced measurement, (uv&, white&) → bool
        Serial.print((long)((long)i)); Serial.print(" s UV "); Serial.print((long)(uv)); Serial.print(" white "); Serial.print((long)(white)); Serial.println();
        delay(1000);
    }

    // --- Gain settings on a constant light level ---
    // HW_GAIN doubles the integration time per step, SW_GAIN accumulates 2^n
    // samples, HIGH_SIGNAL divides the sensitivity by 14.5 for direct sunlight.
    int32_t v[6] = {0, 0, 0, 0, 0, 0};
    size_t n = 0;
    sensor.enable_channels(0x04);                                  // CHAN_LIST, (mask) → bool
    const uint8_t gains[4][3] = {{2, 0, 0}, {6, 0, 0}, {6, 2, 0}, {6, 0, 1}};
    for (int i = 0; i < 4; i++) {
        sensor.configure_channel(2, Si1133Full::MUX_WHITE, gains[i][0], gains[i][1], 0, gains[i][2] != 0);// Channel setup, (channel, adcmux, hw_gain, sw_gain, decim_rate, high_signal) → bool
        sensor.read_channels(v, n);                                // Forced read, (values*, count&) → bool
        Serial.print("hw_gain "); Serial.print((unsigned)(gains[i][0])); Serial.print(" sw_gain "); Serial.print((unsigned)(gains[i][1])); Serial.print(" high_signal "); Serial.print((unsigned)(gains[i][2])); Serial.print(" -> "); Serial.print((long)(v[0])); Serial.println();
    }

    // --- IR photodiodes ---
    int32_t x = 0;
    const uint8_t irs[3] = {Si1133Full::MUX_SMALL_IR, Si1133Full::MUX_MEDIUM_IR, Si1133Full::MUX_LARGE_IR};
    for (int i = 0; i < 3; i++) {
        sensor.read_ir(x, irs[i]);                                 // IR photodiode once, (value&, photodiode) → bool
        Serial.print("IR photodiode "); Serial.print((unsigned)(irs[i])); Serial.print(": "); Serial.print((long)(x)); Serial.println();
    }
    int32_t u1 = 0, w1 = 0;
    sensor.read_uv(u1);                                            // UV on the scratch channel, (value&) → bool
    sensor.read_white(w1);                                         // White on the scratch channel, (value&) → bool
    Serial.print("UV "); Serial.print((long)(u1)); Serial.print(" white "); Serial.print((long)(w1)); Serial.println();

    // --- Autonomous mode with a UV threshold ---
    // Channels 0 and 1 run every second; channel 0 only interrupts when crossing
    // THRESHOLD0, channel 1 on every result.
    sensor.configure_channel(0, Si1133Full::MUX_UV, 9);            // Channel setup, (channel, adcmux, hw_gain) → bool
    sensor.enable_channels(0x03);                                  // CHAN_LIST, (mask) → bool
    sensor.set_threshold(0, 500);                                  // Threshold value, (index, value) → bool
    sensor.set_channel_threshold(0, 1);                            // Threshold select, (channel, index) → bool
    sensor.set_channel_counter(1, 1);                              // Counter for a channel, (channel, index) → bool
    sensor.disable_interrupt(Si1133Full::SOURCE_CHANNEL1);         // IRQ_ENABLE clear, (source) → bool
    sensor.enable_interrupt(Si1133Full::SOURCE_CHANNEL0);          // IRQ_ENABLE set, (source) → bool
    sensor.on_interrupt(on_event);                                 // Subscribe, (callback, intPin=nullptr) → void
    sensor.start_autonomous(0.1f, 10, 10, 10);                     // Autonomous mode, (rate_s, count0, count1, count2) → bool
    for (int i = 0; i < 50; i++) {
        uint8_t m = sensor.poll_interrupt();                       // IRQ_STATUS mask, () → uint8_t
        if (m) {
            sensor.read_channels(v, n, false);                     // Decode current results, (values*, count&, force=false) → bool
            Serial.print("event 0x"); Serial.print((unsigned)(m), HEX); Serial.print(" UV "); Serial.print((long)(v[0])); Serial.print(" white "); Serial.print((long)(v[1])); Serial.println();
        }
        delay(100);
    }
    sensor.off_interrupt();                                        // Unsubscribe, () → void
    sensor.pause();                                                // PAUSE, () → bool
    sensor.enable_interrupt(Si1133Full::SOURCE_CHANNEL1);          // IRQ_ENABLE set, (source) → bool

    // --- Burst mode: four readings in a row ---
    uint8_t mask = 0;
    sensor.set_burst(4);                                           // Burst mode, (count) → bool
    sensor.force();                                                // FORCE without waiting, () → bool
    delay(500);
    sensor.data_ready(mask);                                       // IRQ_STATUS mask, (mask&) → bool
    sensor.read_channels(v, n, false);                             // Decode current results, (values*, count&, force=false) → bool
    Serial.print("burst ready 0x"); Serial.print((unsigned)(mask), HEX); Serial.print(" values "); Serial.print((long)(v[0])); Serial.print(" "); Serial.print((long)(v[1])); Serial.println();
    sensor.set_burst(0);

    // --- Parameter table ---
    uint8_t chan = 0, rh = 0, rl = 0, c2 = 0;
    sensor.write_parameter(0x1E, 7);                               // PARAM_SET, (address, value) → bool
    sensor.read_parameter(0x01, chan);                             // PARAM_QUERY, (address, value&) → bool
    sensor.read_parameter(0x1A, rh);
    sensor.read_parameter(0x1B, rl);
    sensor.read_parameter(0x1E, c2);
    Serial.print("CHAN_LIST 0x"); Serial.print((unsigned)(chan), HEX); Serial.print(" MEASRATE "); Serial.print((unsigned)((rh << 8) | rl)); Serial.print(" MEASCOUNT2 "); Serial.print((unsigned)(c2)); Serial.println();

    // --- Finish ---
    // set_i2c_address would move the chip to another address until the next
    // reset; the demo keeps 0x55 and only shows the call.
    // sensor.set_i2c_address(0x56);                               // Change address, (address) → bool
    sensor.reset();                                                // RESET_SW + defaults, () → bool
    sensor.read(uv, white);                                        // Forced measurement, (uv&, white&) → bool
    Serial.print("after reset UV "); Serial.print((long)(uv)); Serial.print(" white "); Serial.print((long)(white)); Serial.println();
}

void loop() {
}
