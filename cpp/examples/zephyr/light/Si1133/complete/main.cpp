#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "Si1133.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define SI1133_ADDR 0x55

static void on_event(uint8_t status) {
    printk("interrupt 0x%02X\n", (unsigned)(status));
}

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, SI1133_ADDR);
    Si1133Full sensor(connection);                                 // Create Si1133 driver, (connection) → void
    // Constructor stores the connection only; no bus traffic yet.
    sensor.init();                                                 // Check PART_ID, reset, UV + white channels, () → bool
    // Waits 25 ms, verifies PART_ID 0x33, RESET_SW, channel 0 = UV, channel 1 = white, 24-bit outputs.
    uint8_t part = 0, hw = 0, rev = 0;
    sensor.read_id(part, hw, rev);                                 // Identity registers, (part_id&, hw_id&, rev_id&) → bool
    // PART_ID is 0x33; HW_ID bits 4:0 are 0x03 for the Si1133-AA00.
    printk("PART_ID 0x%02X HW_ID 0x%02X REV_ID 0x%02X\n", (unsigned)(part), (unsigned)(hw), (unsigned)(rev));
    uint8_t state = 0, counter = 0;
    bool err = false;
    sensor.read_response(state, err, counter);                     // RESPONSE0 decoded, (state&, error&, counter&) → bool
    // State bits RUNNING/SUSPEND/SLEEP, the CMD_ERR flag and the 4-bit command counter.
    printk("state 0x%02X error %u counter %u\n", (unsigned)(state), (unsigned)(err), (unsigned)(counter));

    int32_t uv = 0, white = 0, x = 0;
    sensor.read(uv, white);                                        // Forced measurement, (uv&, white&) → bool
    // FORCE, wait for both channels in IRQ_STATUS, decode two signed 24-bit counts.
    printk("UV %ld white %ld\n", (long)(uv), (long)(white));
    sensor.read_uv(x);                                             // UV photodiode once, (value&) → bool
    // Uses channel 5 as a scratch channel and restores CHAN_LIST afterwards.
    printk("read_uv %ld\n", (long)(x));
    sensor.read_white(x);                                          // White photodiode once, (value&) → bool
    // Same scratch channel, HW_GAIN 7.
    printk("read_white %ld\n", (long)(x));
    sensor.read_ir(x, Si1133Full::MUX_LARGE_IR);                   // IR photodiode once, (value&, photodiode) → bool
    // Small, medium or large IR photodiode; the small one is the default.
    printk("read_ir %ld\n", (long)(x));

    sensor.configure_channel(2, Si1133Full::MUX_LARGE_WHITE, 4, 1, 0, false, false, 0);// Channel setup, (channel, adcmux, hw_gain, sw_gain, decim_rate, high_signal, bits24, postshift) → bool
    // Writes ADCCONFIG2, ADCSENS2 and ADCPOST2; this channel reports 16-bit unsigned counts.
    sensor.enable_channels(0x07);                                  // CHAN_LIST, (mask) → bool
    // Channels 0, 1 and 2 now run on every FORCE.
    int32_t v[6] = {0, 0, 0, 0, 0, 0};
    size_t n = 0;
    sensor.read_channels(v, n);                                    // Forced read of all channels, (values*, count&, force=true) → bool
    // Results in channel order: 24-bit signed for channels 0/1, 16-bit for channel 2.
    printk("channels %ld %ld %ld\n", (long)(v[0]), (long)(v[1]), (long)(v[2]));
    sensor.force();                                                // FORCE without waiting, () → bool
    // Starts a measurement; the results arrive in HOSTOUT when IRQ_STATUS flags the channels.
    k_msleep(100);
    uint8_t mask = 0;
    sensor.data_ready(mask);                                       // IRQ_STATUS mask, (mask&) → bool
    // Bit c set when channel c finished; this read clears the flags.
    printk("data ready 0x%02X\n", (unsigned)(mask));
    sensor.read_channels(v, n, false);                             // Decode current results, (values*, count&, force=false) → bool
    // No new measurement, only HOSTOUT decoded.

    sensor.set_threshold(0, 2000);                                 // Threshold value, (index, value) → bool
    // THRESHOLD0 = 2000 counts (16-bit).
    sensor.set_channel_threshold(0, 1);                            // Threshold select, (channel, index) → bool
    // Channel 0 interrupts only when crossing THRESHOLD0 (index 1); 0 turns it off.
    sensor.set_channel_threshold(0, 0);
    sensor.set_channel_counter(2, 2);                              // Counter for a channel, (channel, index) → bool
    // Channel 2 uses MEASCOUNT1 in autonomous mode; 0 keeps a channel forced-only.
    sensor.start_autonomous(0.1f, 10, 5, 1);                       // Autonomous mode, (rate_s, count0, count1, count2) → bool
    // MEASRATE = 0.1 s; channels 0/1 run every 1 s (MEASCOUNT0 = 10), channel 2 every 0.5 s.
    k_msleep(1500);
    printk("poll 0x%02X\n", (unsigned)(sensor.poll_interrupt()));  // IRQ_STATUS mask, () → uint8_t
    // Same as data_ready; also releases the INT pin.
    sensor.pause();                                                // PAUSE, () → bool
    // Stops the autonomous measurements.
    sensor.set_burst(4);                                           // Burst mode, (count) → bool
    // Four readings per burst at MEASRATE; 0 disables burst mode.
    sensor.set_burst(0);

    sensor.write_parameter(0x1E, 3);                               // PARAM_SET, (address, value) → bool
    // Direct parameter-table access: MEASCOUNT2 = 3.
    uint8_t p = 0;
    sensor.read_parameter(0x1E, p);                                // PARAM_QUERY, (address, value&) → bool
    // Reads it back through RESPONSE1.
    printk("MEASCOUNT2 %u\n", (unsigned)(p));

    sensor.disable_interrupt(Si1133Full::SOURCE_CHANNEL2);         // IRQ_ENABLE clear, (source) → bool
    // Channel 2 no longer drives INT.
    sensor.enable_interrupt(Si1133Full::SOURCE_CHANNEL2);          // IRQ_ENABLE set, (source) → bool
    // Channel 2 drives INT again.
    sensor.on_interrupt(on_event);                                 // Subscribe, (callback, intPin=nullptr) → void
    // INT falling edge via the connection's pin; without a pin, poll with poll_interrupt().
    sensor.read_channels(v, n);
    k_msleep(100);
    sensor.off_interrupt();                                        // Unsubscribe, () → void
    // Removes the edge handler.

    sensor.reset();                                                // RESET_SW + defaults, () → bool
    // Parameter table back to power-up values, then channel 0 = UV, channel 1 = white again.
    // sensor.set_i2c_address(0x56);                               // Change address, (address) → bool
    // Volatile; the chip answers on the new address at once, so a new connection is needed.
    return 0;
}
