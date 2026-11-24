//! Complete example for the APDS-9250 — every Apds9250Full method once.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::light::{Apds9250Full, Apds9250Channel, APDS9250_SOURCE_VARIATION, APDS9250_I2C_ADDRESS};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(APDS9250_I2C_ADDRESS);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    #[allow(unused_mut)]
    let mut delay = Delay;
    #[allow(unused_macros)]
    macro_rules! sleep_ms { ($n:expr) => { std::thread::sleep(std::time::Duration::from_millis($n)) }; }

    // Create APDS-9250 Full driver, (i2c, addr=0x52) → Apds9250Full
    let mut chip = Apds9250Full::new(dev, addr);
    // Check PART_ID and start colour mode, (delay) → Result<(), Apds9250Error>
    chip.init(&mut delay).expect("init");

    // Read PART_ID, () → Result<(u8, u8)>  (part, revision) = (0xB, 0x5)
    println!("part/revision {:?}", chip.part_id().expect("part_id"));
    // Read the four channels, (delay) → Result<Apds9250Counts>
    println!("{:?}", chip.read(&mut delay).expect("read"));
    // Read single channels, () → Result<u32>
    println!("red {}", chip.read_red().expect("read_red"));
    println!("green {}", chip.read_green().expect("read_green"));
    println!("blue {}", chip.read_blue().expect("read_blue"));
    println!("ir {}", chip.read_ir().expect("read_ir"));
    // Query LS_DATA (clears the status flags), () → Result<bool>
    println!("ready {}", chip.data_ready().expect("data_ready"));
    // Block-read without waiting, () → Result<Apds9250Counts>
    println!("{:?}", chip.read_channels().expect("read_channels"));

    // Select ALS/IR mode (false) or colour mode (true), (colour) → Result<()>
    chip.set_mode(false).expect("set_mode");
    chip.set_mode(true).expect("set_mode");
    // Standby / activate, () → Result<()>
    chip.disable().expect("disable");
    chip.enable().expect("enable");

    // Set the ADC resolution, (bits 13/16/17/18/19/20) → Result<(), Apds9250Error>
    chip.set_resolution(16).expect("set_resolution");
    // Set the measurement rate, (ms 25/50/100/200/500/1000/2000) → Result<(), Apds9250Error>
    chip.set_measurement_rate(200).expect("set_measurement_rate");
    // Set the gain, (1/3/6/9/18) → Result<(), Apds9250Error>
    chip.set_gain(6).expect("set_gain");
    // Read the settings back, () → Result<u8/u16/f32>
    println!("resolution {} bit", chip.get_resolution().expect("get_resolution"));
    println!("rate {} ms", chip.get_measurement_rate().expect("get_measurement_rate"));
    println!("gain {}", chip.get_gain().expect("get_gain"));
    println!("integration {} s", chip.integration_time().expect("integration_time"));
    // Counts per gain-second, (counts) → Result<f32>
    let green = chip.read_green().expect("read_green");
    println!("normalized {}", chip.normalized(green).expect("normalized"));

    // Set the 20-bit threshold window, (low, high) → Result<(), Apds9250Error>
    chip.set_thresholds(1000, 100_000).expect("set_thresholds");
    // Read the window, () → Result<(u32, u32)>
    println!("window {:?}", chip.get_thresholds().expect("get_thresholds"));
    // Choose the monitored channel, (Apds9250Channel) → Result<()>
    chip.set_interrupt_channel(Apds9250Channel::Green).expect("set_interrupt_channel");
    // Set the persistence 1–16, (count) → Result<(), Apds9250Error>
    chip.set_persistence(2).expect("set_persistence");
    // Set the variation threshold 8…1024 (power of two), (counts) → Result<(), Apds9250Error>
    chip.set_variance(64).expect("set_variance");
    // Enable the interrupt, (source) → Result<()>
    chip.enable_interrupt(APDS9250_SOURCE_VARIATION).expect("enable_interrupt");
    sleep_ms!(2000);
    // Read and clear MAIN_STATUS (0x20 power-on, 0x10 interrupt, 0x08 data), () → Result<u8>
    println!("poll 0x{:02X}", chip.poll_interrupt().expect("poll_interrupt"));
    chip.disable_interrupt().expect("disable_interrupt");
    // Raw MAIN_STATUS, () → Result<u8>
    println!("status 0x{:02X}", chip.status().expect("status"));
    // Software reset (all registers to defaults, sensor in standby), (delay) → Result<()>
    chip.soft_reset(&mut delay).expect("soft_reset");
}
