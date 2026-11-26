//! Complete example for the Si1133 — every API method of the Full driver.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::light::si1133;
use periph::chips::light::Si1133Full;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x55);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    #[allow(unused_mut)]
    let mut delay = Delay;
    #[allow(unused_macros)]
    macro_rules! sleep_ms { ($n:expr) => { std::thread::sleep(std::time::Duration::from_millis($n)) }; }

    // Create Si1133 driver, (i2c, addr=0x55) → Si1133Full
    // Stores the bus only; no bus traffic yet.
    let mut chip = Si1133Full::new(dev, addr);
    // Check PART_ID, reset, UV + white channels, (delay) → Result<()>
    // Waits 25 ms, verifies PART_ID 0x33, RESET_SW, channel 0 = UV, channel 1 = white, 24-bit outputs.
    chip.init(&mut delay).expect("init");

    // Identity registers, () → Result<(u8 part_id, u8 hw_id, u8 rev_id)>
    // PART_ID is 0x33; HW_ID bits 4:0 are 0x03 for the Si1133-AA00.
    println!("{:?}", chip.read_id().expect("id"));
    // RESPONSE0 decoded, () → Result<Response {state, error, counter}>
    // State bits RUNNING/SUSPEND/SLEEP, the CMD_ERR flag and the 4-bit command counter.
    println!("{:?}", chip.read_response().expect("response"));

    // Forced measurement, (delay) → Result<Reading {uv counts, white counts}>
    // FORCE, wait for both channels in IRQ_STATUS, decode two signed 24-bit counts.
    println!("{:?}", chip.read(&mut delay).expect("read"));
    // UV photodiode once, (delay) → Result<i32 counts>
    // Uses channel 5 as a scratch channel and restores CHAN_LIST afterwards.
    println!("{}", chip.read_uv(&mut delay).expect("uv"));
    // White photodiode once, (delay) → Result<i32 counts>
    // Same scratch channel, HW_GAIN 7.
    println!("{}", chip.read_white(&mut delay).expect("white"));
    // IR photodiode once, (delay, photodiode) → Result<i32 counts>
    // Small, medium or large IR photodiode.
    println!("{}", chip.read_ir(&mut delay, si1133::MUX_LARGE_IR).expect("ir"));

    // Channel setup, (delay, channel, adcmux, hw_gain, sw_gain, decim_rate, high_signal, bits24, postshift) → Result<()>
    // Writes ADCCONFIG2, ADCSENS2 and ADCPOST2; this channel reports 16-bit unsigned counts.
    chip.configure_channel(&mut delay, 2, si1133::MUX_LARGE_WHITE, 4, 1, 0, false, false, 0).expect("configure");
    // CHAN_LIST, (delay, mask) → Result<()>
    // Channels 0, 1 and 2 now run on every FORCE.
    chip.enable_channels(&mut delay, 0x07).expect("enable");
    // Forced read of all channels, (delay, force=true) → Result<Channels>
    // Results in channel order: 24-bit signed for channels 0/1, 16-bit for channel 2.
    println!("{:?}", chip.read_channels(&mut delay, true).expect("channels").as_slice());
    // FORCE without waiting, (delay) → Result<()>
    // Starts a measurement; the results arrive in HOSTOUT when IRQ_STATUS flags the channels.
    chip.force(&mut delay).expect("force");
    sleep_ms!(100);
    // IRQ_STATUS mask, () → Result<u8>
    // Bit c set when channel c finished; this read clears the flags.
    println!("data ready 0x{:02X}", chip.data_ready().expect("data_ready"));
    // Decode current results, (delay, force=false) → Result<Channels>
    // No new measurement, only HOSTOUT decoded.
    println!("{:?}", chip.read_channels(&mut delay, false).expect("channels").as_slice());

    // Threshold value, (delay, index, value) → Result<()>
    // THRESHOLD0 = 2000 counts (16-bit).
    chip.set_threshold(&mut delay, 0, 2000).expect("threshold");
    // Threshold select, (delay, channel, index) → Result<()>
    // Channel 0 interrupts only when crossing THRESHOLD0 (index 1); 0 turns it off.
    chip.set_channel_threshold(&mut delay, 0, 1).expect("channel_threshold");
    chip.set_channel_threshold(&mut delay, 0, 0).expect("channel_threshold");
    // Counter for a channel, (delay, channel, index) → Result<()>
    // Channel 2 uses MEASCOUNT1 in autonomous mode; 0 keeps a channel forced-only.
    chip.set_channel_counter(&mut delay, 2, 2).expect("counter");
    // Autonomous mode, (delay, rate_s, counters) → Result<()>
    // MEASRATE = 0.1 s; channels 0/1 run every 1 s (MEASCOUNT0 = 10), channel 2 every 0.5 s.
    chip.start_autonomous(&mut delay, 0.1, [10, 5, 1]).expect("start");
    sleep_ms!(1500);
    // IRQ_STATUS mask, () → Result<u8>
    // Same as data_ready; also releases the INT pin.
    println!("poll 0x{:02X}", chip.poll_interrupt().expect("poll"));
    // PAUSE, (delay) → Result<()>
    // Stops the autonomous measurements.
    chip.pause(&mut delay).expect("pause");
    // Burst mode, (delay, count) → Result<()>
    // Four readings per burst at MEASRATE; 0 disables burst mode.
    chip.set_burst(&mut delay, 4).expect("burst");
    chip.set_burst(&mut delay, 0).expect("burst");

    // PARAM_SET, (delay, address, value) → Result<()>
    // Direct parameter-table access: MEASCOUNT2 = 3.
    chip.write_parameter(&mut delay, 0x1E, 3).expect("write_parameter");
    // PARAM_QUERY, (delay, address) → Result<u8>
    // Reads it back through RESPONSE1.
    println!("MEASCOUNT2 {}", chip.read_parameter(&mut delay, 0x1E).expect("read_parameter"));

    // IRQ_ENABLE clear, (source) → Result<()>
    // Channel 2 no longer drives INT.
    chip.disable_interrupt(si1133::SOURCE_CHANNEL2).expect("disable");
    // IRQ_ENABLE set, (source) → Result<()>
    // Channel 2 drives INT again; wire INT to a pin and call poll_interrupt from its handler.
    chip.enable_interrupt(si1133::SOURCE_CHANNEL2).expect("enable");

    // RESET_SW + defaults, (delay) → Result<()>
    // Parameter table back to power-up values, then channel 0 = UV, channel 1 = white again.
    chip.reset(&mut delay).expect("reset");
    // Change address, (address) → Result<()>
    // Volatile; the chip answers on the new address at once, so a new driver is needed.
    // chip.set_i2c_address(0x56).expect("address");
}
