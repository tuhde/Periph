//! Complete example for the MPL3115A2 — every API method of the Full driver.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::pressure::mpl3115a2::{FIFO_CIRCULAR, FIFO_OFF, SOURCE_DRDY};
use periph::chips::pressure::MPL3115A2Full;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x60);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    #[allow(unused_mut)]
    let mut delay = Delay;
    #[allow(unused_macros)]
    macro_rules! sleep_ms { ($n:expr) => { std::thread::sleep(std::time::Duration::from_millis($n)) }; }

    // Create MPL3115A2 driver, (i2c, addr=0x60) → MPL3115A2
    let mut chip = MPL3115A2Full::new(dev, addr);
    // Check the chip and apply the defaults, (delay) → Result<()>; 0xC4 identity, PT_DATA_CFG = 0x07, barometer, OSR 128
    chip.init(&mut delay).expect("init");
    // Read WHO_AM_I, () → Result<u8>; 0xC4
    println!("who_am_i 0x{:02X}", chip.who_am_i().expect("who_am_i"));
    // Set the oversample ratio, (ratio 1/2/4/…/128) → Result<()>; 128 gives 0.25 Pa resolution at 512 ms per sample
    chip.set_oversample(32).expect("oversample");
    // Get the oversample ratio, () → u8
    println!("oversample {}", chip.get_oversample());
    // Set the sea-level reference, (pa) → Result<()>; BAR_IN in 2 Pa units
    chip.set_sea_level_pressure(101325.0).expect("sea level");
    // Trim the offsets, (pressure_pa, temperature_c, altitude_m) → Result<()>
    chip.set_offsets(0.0, 0.0, 0).expect("offsets");
    // One-shot values, (delay) → Result<f32 Pa> / Result<f32 °C>
    println!("{:.2} Pa {:.2} C", chip.read_pressure(&mut delay).expect("p"), chip.read_temperature(&mut delay).expect("t"));
    // Both together, (delay) → Result<(f32 Pa, f32 °C)>
    let (p, t) = chip.read(&mut delay).expect("read");
    println!("{:.2} Pa {:.2} C", p, t);
    // Altimeter reading, (delay) → Result<f32 m>; switches to altimeter mode
    println!("altitude {:.2} m", chip.read_altitude(&mut delay).expect("altitude"));
    // Back to barometer mode, (altimeter) → Result<()>
    chip.set_mode(false).expect("mode");
    // Start periodic acquisition, (step_exp 0-15) → Result<()>; one sample every 2^n seconds
    chip.start_continuous(0).expect("start");
    // Check ACTIVE mode, () → Result<bool>
    println!("active {}", chip.is_active().expect("active"));
    // Wait for PTDR and read, (delay) → Result<(f32, f32)>
    println!("{:?}", chip.read_continuous(&mut delay).expect("continuous"));
    // Data-ready flag and change since the previous sample, () → Result<bool> / Result<(f32, f32)>
    println!("{} {:?}", chip.data_ready().expect("ready"), chip.read_delta().expect("delta"));
    // Back to STANDBY, () → Result<()>
    chip.stop_continuous().expect("stop");
    // Threshold targets, (value, window, temperature_c, temperature_window) → Result<()>
    chip.set_target(100000.0, 200.0, -5, 3).expect("target");
    // Use the current output as target, () → Result<()>
    chip.load_current_as_target().expect("load");
    // Captured extremes, () → Result<MinMax>
    println!("{:?}", chip.read_min_max().expect("min_max"));
    // Clear the extremes, () → Result<()>
    chip.clear_min_max().expect("clear");
    // Configure the FIFO, (mode 0/1/2, watermark 0-63) → Result<()>
    chip.fifo_setup(FIFO_CIRCULAR, 16).expect("fifo");
    // FIFO state, () → Result<(bool overflow, bool watermark, u8 count)>
    println!("{:?}", chip.fifo_status().expect("fifo_status"));
    // Drain the FIFO, (&mut [(f32, f32)]) → Result<usize>
    let mut samples = [(0.0f32, 0.0f32); 32];
    println!("fifo {} samples", chip.fifo_read(&mut samples).expect("fifo_read"));
    // FIFO off, (mode, watermark) → Result<()>
    chip.fifo_setup(FIFO_OFF, 0).expect("fifo off");
    // INT polarity / drive, (polarity1, open_drain1, polarity2, open_drain2) → Result<()>
    chip.set_interrupt_pins(false, false, false, false).expect("pins");
    // Route a source to INT1, (source, pin 1/2) → Result<()>
    chip.enable_interrupt(SOURCE_DRDY, 1).expect("enable");
    // Read INT_SOURCE, () → Result<u8>
    println!("INT_SOURCE 0x{:02X}", chip.poll_interrupt().expect("poll"));
    // Disable the source, (source) → Result<()>
    chip.disable_interrupt(SOURCE_DRDY).expect("disable");
    // Software reset and defaults, (delay) → Result<()>
    chip.reset(&mut delay).expect("reset");
}
