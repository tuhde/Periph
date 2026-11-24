//! Demo for the MPL3115A2 — weather and altitude station.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::pressure::mpl3115a2::{FIFO_CIRCULAR, FIFO_OFF, SOURCE_TTH};
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
    // Check the chip and apply the defaults, (delay) → Result<()>
    chip.init(&mut delay).expect("init");

    // --- Weather and altitude station ---
    // Sets the local sea-level pressure, takes three barometer readings, switches to
    // altimeter mode for three altitude readings, collects 16 samples in the circular
    // FIFO and prints their minimum, maximum and mean, then arms a temperature
    // threshold on INT1 and reports the captured extremes.
    // Set the sea-level reference, (pa) → Result<()>
    chip.set_sea_level_pressure(101325.0).expect("sea level");
    for _ in 0..3 {
        // Read pressure and temperature, (delay) → Result<(f32 Pa, f32 °C)>
        let (p, t) = chip.read(&mut delay).expect("read");
        println!("{:.2} Pa  {:.2} C", p, t);
        sleep_ms!(5000);
    }
    for _ in 0..3 {
        // Read altitude, (delay) → Result<f32 m>
        println!("altitude {:.2} m", chip.read_altitude(&mut delay).expect("altitude"));
    }
    // Back to barometer mode, (altimeter) → Result<()>
    chip.set_mode(false).expect("mode");
    // Faster conversions, (ratio) → Result<()>
    chip.set_oversample(16).expect("oversample");
    // Circular FIFO with watermark 16, (mode, watermark) → Result<()>
    chip.fifo_setup(FIFO_CIRCULAR, 16).expect("fifo");
    // One sample per second, (step_exp 0-15) → Result<()>
    chip.start_continuous(0).expect("start");
    // Wait for the watermark flag, () → Result<(bool, bool, u8)>
    while !chip.fifo_status().expect("fifo_status").1 {
        sleep_ms!(1000);
    }
    // Drain the FIFO, (&mut [(f32, f32)]) → Result<usize>
    let mut samples = [(0.0f32, 0.0f32); 32];
    let n = chip.fifo_read(&mut samples).expect("fifo_read");
    let values = &samples[..n];
    let lo = values.iter().fold(f32::MAX, |a, s| a.min(s.0));
    let hi = values.iter().fold(f32::MIN, |a, s| a.max(s.0));
    let mean = values.iter().map(|s| s.0).sum::<f32>() / n.max(1) as f32;
    println!("n={} min {:.2} max {:.2} mean {:.2} Pa", n, lo, hi, mean);
    // Temperature target, (value, window, temperature_c, temperature_window) → Result<()>
    chip.set_target(0.0, 0.0, samples[n.saturating_sub(1)].1 as i8 + 1, 0).expect("target");
    // Route the temperature threshold to INT1, (source, pin) → Result<()>
    chip.enable_interrupt(SOURCE_TTH, 1).expect("enable");
    // Read INT_SOURCE, () → Result<u8>
    println!("INT_SOURCE 0x{:02X}", chip.poll_interrupt().expect("poll"));
    // Back to STANDBY, () → Result<()>
    chip.stop_continuous().expect("stop");
    // FIFO off, (mode, watermark) → Result<()>
    chip.fifo_setup(FIFO_OFF, 0).expect("fifo off");
    // Captured extremes, () → Result<MinMax>
    println!("{:?}", chip.read_min_max().expect("min_max"));
}
