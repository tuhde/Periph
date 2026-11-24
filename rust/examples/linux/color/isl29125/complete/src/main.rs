//! Complete example for the ISL29125 — every API method of the Full driver.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::color::{Isl29125Channel, Isl29125Full, Isl29125Mode, ISL29125_I2C_ADDRESS, ISL29125_SOURCE_THRESHOLD};

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

    println!("device id 0x{:02X}", chip.device_id().expect("device_id")); // Read DEVICE_ID, () → Result<u8>
    // Read the three channels, (delay) → Result<Isl29125Counts>
    let c = chip.read(&mut delay).expect("read");
    println!("R {} G {} B {}", c.red, c.green, c.blue);
    // Read the red channel, () → Result<u16>
    println!("red {}", chip.read_red().expect("read_red"));
    // Read the green channel, () → Result<u16>
    println!("green {}", chip.read_green().expect("read_green"));
    // Read the blue channel, () → Result<u16>
    println!("blue {}", chip.read_blue().expect("read_blue"));
    // Block-read without waiting, () → Result<Isl29125Counts>
    println!("{:?}", chip.read_rgb().expect("read_rgb"));
    // Query CONVENF, () → Result<bool>; clears the status flags on the chip
    println!("ready {}", chip.data_ready().expect("data_ready"));
    // Select the channels, (mode) → Result<()>; PowerDown, Standby, Green, Red, Blue, Rgb, GreenRed, GreenBlue
    chip.set_mode(Isl29125Mode::Green).expect("set_mode");
    chip.set_mode(Isl29125Mode::Rgb).expect("set_mode");
    // Set the full-scale range, (lux) → Result<()>; 375 or 10000
    chip.set_range(375).expect("set_range");
    chip.set_range(10_000).expect("set_range");
    // Set the ADC resolution, (bits) → Result<()>; 12 or 16
    chip.set_resolution(12).expect("set_resolution");
    chip.set_resolution(16).expect("set_resolution");
    // Select the conversion start, (external) → Result<()>; true: rising edge of INT starts the conversion
    chip.set_sync(false).expect("set_sync");
    // Set the IR compensation, (offset, adjust) → Result<()>; adjust 0-63, offset selects codes 106-169
    chip.set_ir_compensation(true, 0x3F).expect("set_ir_compensation");
    // Set the threshold window, (low, high) → Result<()>
    chip.set_thresholds(1000, 50_000).expect("set_thresholds");
    // Read the window, () → Result<(u16, u16)>
    println!("window {:?}", chip.get_thresholds().expect("get_thresholds"));
    // Choose the monitored channel, (channel) → Result<()>; None, Green, Red or Blue
    chip.set_interrupt_channel(Isl29125Channel::Green).expect("set_interrupt_channel");
    // Set the persistence, (count) → Result<()>; 1, 2, 4 or 8 integration cycles
    chip.set_persistence(2).expect("set_persistence");
    // Enable the interrupt, (source) → Result<()>; ISL29125_SOURCE_THRESHOLD or ISL29125_SOURCE_CONVERSION
    chip.enable_interrupt(ISL29125_SOURCE_THRESHOLD).expect("enable_interrupt");
    sleep_ms!(2000);
    // Read and clear STATUS, () → Result<u8>; 0x04 brown-out, 0x02 conversion, 0x01 threshold
    println!("poll 0x{:02X}", chip.poll_interrupt().expect("poll_interrupt"));
    // Disable the interrupt, (source) → Result<()>
    chip.disable_interrupt(ISL29125_SOURCE_THRESHOLD).expect("disable_interrupt");
    // Raw STATUS, () → Result<u8>
    println!("status 0x{:02X}", chip.status().expect("status"));
    chip.set_mode(Isl29125Mode::PowerDown).expect("set_mode");
    // Software reset, (delay) → Result<()>; all registers to defaults
    chip.soft_reset(&mut delay).expect("soft_reset");
}
