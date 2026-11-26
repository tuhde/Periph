//! Demo for the Si1133 — UV and daylight monitor with gains, IR photodiodes, autonomous and burst modes.

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
    let mut chip = Si1133Full::new(dev, addr);
    // Check PART_ID, reset, UV + white channels, (delay) → Result<()>
    chip.init(&mut delay).expect("init");

    // --- Identity ---
    // PART_ID 0x33 identifies the Si1133; HW_ID and REV_ID give silicon and
    // product revision. RESPONSE0 shows the sequencer state and command counter.
    // Identity registers, () → Result<(u8, u8, u8)>
    let (part, hw, rev) = chip.read_id().expect("id");
    println!("PART_ID 0x{:02X}  HW_ID 0x{:02X}  REV_ID 0x{:02X}", part, hw, rev);
    // RESPONSE0 decoded, () → Result<Response>
    println!("response {:?}", chip.read_response().expect("response"));

    // --- UV and daylight monitor: 20 s at 1 Hz ---
    // The counts are raw ADC values; a UV index needs Silicon Labs' calibration
    // for the product's window, so watch the trend.
    for i in 0..20 {
        // Forced measurement, (delay) → Result<Reading {uv counts, white counts}>
        let m = chip.read(&mut delay).expect("read");
        println!("{:2} s  UV {:8}  white {:8}", i, m.uv, m.white);
        sleep_ms!(1000);
    }

    // --- Gain settings on a constant light level ---
    // HW_GAIN doubles the integration time per step, SW_GAIN accumulates 2^n
    // samples, HIGH_SIGNAL divides the sensitivity by 14.5 for direct sunlight.
    // CHAN_LIST, (delay, mask) → Result<()>
    chip.enable_channels(&mut delay, 0x04).expect("enable");
    for (hw_gain, sw_gain, high) in [(2u8, 0u8, false), (6, 0, false), (6, 2, false), (6, 0, true)] {
        // Channel setup, (delay, channel, adcmux, hw_gain, sw_gain, decim_rate, high_signal, bits24, postshift) → Result<()>
        chip.configure_channel(&mut delay, 2, si1133::MUX_WHITE, hw_gain, sw_gain, 0, high, true, 0).expect("configure");
        // Forced read, (delay, force) → Result<Channels>
        let c = chip.read_channels(&mut delay, true).expect("channels");
        println!("hw_gain {} sw_gain {} high_signal {} -> {}", hw_gain, sw_gain, high, c.values[0]);
    }

    // --- IR photodiodes ---
    for (name, mux) in [("small", si1133::MUX_SMALL_IR), ("medium", si1133::MUX_MEDIUM_IR), ("large", si1133::MUX_LARGE_IR)] {
        // IR photodiode once, (delay, photodiode) → Result<i32 counts>
        println!("IR {:6} {}", name, chip.read_ir(&mut delay, mux).expect("ir"));
    }
    // Scratch-channel reads, (delay) → Result<i32 counts>
    let uv = chip.read_uv(&mut delay).expect("uv");
    let white = chip.read_white(&mut delay).expect("white");
    println!("UV {}, white {}", uv, white);

    // --- Autonomous mode with a UV threshold ---
    // Channels 0 and 1 run every second; channel 0 only interrupts when crossing
    // THRESHOLD0, channel 1 on every result. The Rust driver has no callback:
    // poll_interrupt is called from the loop (or from an INT pin handler).
    // Channel setup, (delay, channel, adcmux, ...) → Result<()>
    chip.configure_channel(&mut delay, 0, si1133::MUX_UV, 9, 0, 0, false, true, 0).expect("configure");
    // CHAN_LIST, (delay, mask) → Result<()>
    chip.enable_channels(&mut delay, 0x03).expect("enable");
    // Threshold value, (delay, index, value) → Result<()>
    chip.set_threshold(&mut delay, 0, 500).expect("threshold");
    // Threshold select, (delay, channel, index) → Result<()>
    chip.set_channel_threshold(&mut delay, 0, 1).expect("channel_threshold");
    // Counter for a channel, (delay, channel, index) → Result<()>
    chip.set_channel_counter(&mut delay, 1, 1).expect("counter");
    // IRQ_ENABLE clear / set, (source) → Result<()>
    chip.disable_interrupt(si1133::SOURCE_CHANNEL1).expect("disable");
    chip.enable_interrupt(si1133::SOURCE_CHANNEL0).expect("enable");
    // Autonomous mode, (delay, rate_s, counters) → Result<()>
    chip.start_autonomous(&mut delay, 0.1, [10, 10, 10]).expect("start");
    for _ in 0..50 {
        // IRQ_STATUS mask, () → Result<u8>
        let status = chip.poll_interrupt().expect("poll");
        if status != 0 {
            // Decode current results, (delay, force=false) → Result<Channels>
            let c = chip.read_channels(&mut delay, false).expect("channels");
            println!("interrupt 0x{:02X}  values {:?}", status, c.as_slice());
        }
        sleep_ms!(100);
    }
    // PAUSE, (delay) → Result<()>
    chip.pause(&mut delay).expect("pause");
    chip.enable_interrupt(si1133::SOURCE_CHANNEL1).expect("enable");

    // --- Burst mode: four readings in a row ---
    // Burst mode, (delay, count) → Result<()>
    chip.set_burst(&mut delay, 4).expect("burst");
    // FORCE without waiting, (delay) → Result<()>
    chip.force(&mut delay).expect("force");
    sleep_ms!(500);
    // IRQ_STATUS mask, () → Result<u8>
    println!("burst ready 0x{:02X}", chip.data_ready().expect("data_ready"));
    println!("burst values {:?}", chip.read_channels(&mut delay, false).expect("channels").as_slice());
    chip.set_burst(&mut delay, 0).expect("burst");

    // --- Parameter table ---
    // PARAM_SET, (delay, address, value) → Result<()>
    chip.write_parameter(&mut delay, 0x1E, 7).expect("write_parameter");
    // PARAM_QUERY, (delay, address) → Result<u8>
    let chan = chip.read_parameter(&mut delay, 0x01).expect("read_parameter");
    let rate = ((chip.read_parameter(&mut delay, 0x1A).expect("p") as u16) << 8) | chip.read_parameter(&mut delay, 0x1B).expect("p") as u16;
    let c2 = chip.read_parameter(&mut delay, 0x1E).expect("read_parameter");
    println!("CHAN_LIST 0x{:02X}  MEASRATE {}  MEASCOUNT2 {}", chan, rate, c2);

    // --- Finish ---
    // set_i2c_address would move the chip to another address until the next
    // reset; the demo keeps 0x55 and only shows the call.
    // chip.set_i2c_address(0x56).expect("address");
    // RESET_SW + defaults, (delay) → Result<()>
    chip.reset(&mut delay).expect("reset");
    println!("after reset {:?}", chip.read(&mut delay).expect("read"));
}
