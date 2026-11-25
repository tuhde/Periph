//! Demo for the ISL29125 — colour sampler with range, resolution and IR-compensation steps and polled interrupts.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::color::{Isl29125Channel, Isl29125Full, Isl29125Mode, ISL29125_I2C_ADDRESS, ISL29125_SOURCE_THRESHOLD, ISL29125_STATUS_THRESHOLD};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(ISL29125_I2C_ADDRESS);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    #[allow(unused_mut)]
    let mut delay = Delay;
    #[allow(unused_macros)]
    macro_rules! sleep_ms { ($n:expr) => { std::thread::sleep(std::time::Duration::from_millis($n)) }; }

    // Create ISL29125 Full driver, (i2c, addr=0x44) → Isl29125Full
    let mut chip = Isl29125Full::new(dev, addr);
    // Check DEVICE_ID and start RGB conversion, () → Result<(), Isl29125Error>
    chip.init().expect("init");

    // --- Colour sampler ---
    // Prints the device ID, samples R/G/B as a normalised colour, compares the
    // 375 lux and 10 000 lux ranges and the 12/16-bit resolutions, varies the IR
    // compensation, reads green only, arms a threshold interrupt on green with
    // persistence 2 (polled through poll_interrupt), then powers down and resets.
    // Read DEVICE_ID, () → Result<u8>
    println!("device id 0x{:02X}", chip.device_id().expect("device_id"));
    for _ in 0..20 {
        // Read the three channels, (delay) → Result<Isl29125Counts>
        let c = chip.read(&mut delay).expect("read");
        let top = c.red.max(c.green).max(c.blue).max(1) as u32;
        println!(
            "#{:02x}{:02x}{:02x} {} {} {}",
            c.red as u32 * 255 / top,
            c.green as u32 * 255 / top,
            c.blue as u32 * 255 / top,
            c.red,
            c.green,
            c.blue
        );
        sleep_ms!(500);
    }
    for lux in [375u16, 10_000] {
        // Set the range, (lux) → Result<()>
        chip.set_range(lux).expect("set_range");
        sleep_ms!(400);
        // Read without waiting, () → Result<Isl29125Counts>
        println!("{} lux range {:?}", lux, chip.read_rgb().expect("read_rgb"));
    }
    for bits in [12u8, 16] {
        // Set the resolution, (bits) → Result<()>
        chip.set_resolution(bits).expect("set_resolution");
        sleep_ms!(400);
        println!("{} bit full scale {} {:?}", bits, (1u32 << bits) - 1, chip.read_rgb().expect("read_rgb"));
    }
    chip.set_range(10_000).expect("set_range");
    for adjust in [0u8, 0x20, 0x3F] {
        // Set the IR compensation, (offset, adjust) → Result<()>
        chip.set_ir_compensation(false, adjust).expect("set_ir_compensation");
        sleep_ms!(400);
        println!("IR compensation {} {:?}", adjust, chip.read_rgb().expect("read_rgb"));
    }
    chip.set_ir_compensation(true, 0x3F).expect("set_ir_compensation");
    // Green only, (mode) → Result<()>
    chip.set_mode(Isl29125Mode::Green).expect("set_mode");
    sleep_ms!(300);
    // Read green, () → Result<u16>
    println!("green only {}", chip.read_green().expect("read_green"));
    chip.set_mode(Isl29125Mode::Rgb).expect("set_mode");
    // Query CONVENF, () → Result<bool>
    println!("ready {}", chip.data_ready().expect("data_ready"));
    let base = chip.read_green().expect("read_green");
    // Set the window, (low, high) → Result<()>
    chip.set_thresholds(base - base / 4, base.saturating_add(base / 4)).expect("set_thresholds");
    // Read the window, () → Result<(u16, u16)>
    println!("window {:?}", chip.get_thresholds().expect("get_thresholds"));
    // Choose the channel, (channel) → Result<()>
    chip.set_interrupt_channel(Isl29125Channel::Green).expect("set_interrupt_channel");
    // Set the persistence, (count) → Result<()>
    chip.set_persistence(2).expect("set_persistence");
    // Enable the interrupt, (source) → Result<()>
    chip.enable_interrupt(ISL29125_SOURCE_THRESHOLD).expect("enable_interrupt");
    for _ in 0..20 {
        sleep_ms!(500);
        // Read and clear STATUS, () → Result<u8>
        let st = chip.poll_interrupt().expect("poll_interrupt");
        if st & ISL29125_STATUS_THRESHOLD != 0 {
            println!("window crossed, green {}", chip.read_green().expect("read_green"));
        }
    }
    // Disable the interrupt, (source) → Result<()>
    chip.disable_interrupt(ISL29125_SOURCE_THRESHOLD).expect("disable_interrupt");
    println!("status 0x{:02X}", chip.status().expect("status"));
    // Power down, (mode) → Result<()>
    chip.set_mode(Isl29125Mode::PowerDown).expect("set_mode");
    // Software reset, (delay) → Result<()>
    chip.soft_reset(&mut delay).expect("soft_reset");
}
