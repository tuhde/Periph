//! Complete example for the BH1750 — every BH1750Full method once.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::light::{BH1750Full, BH1750Mode as Mode};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x23);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    #[allow(unused_mut)]
    let mut delay = Delay;
    #[allow(unused_macros)]
    macro_rules! sleep_ms { ($n:expr) => { std::thread::sleep(std::time::Duration::from_millis($n)) }; }

    // Create BH1750 driver, (i2c, addr=0x23) → BH1750
    let mut chip = BH1750Full::new(dev, addr);
    // Power on, () → Result<()>
    chip.init().expect("init");

    // Measure illuminance, (delay) → Result<f32 lx>; one-time H-resolution (1 lx), waits 180 ms
    println!("illuminance {:.1} lx", chip.read(&mut delay).expect("read"));
    // Read the raw count, (delay) → Result<u16>; 16-bit, one one-time measurement in the current mode
    println!("raw {}", chip.read_raw(&mut delay).expect("raw"));
    // One-time measurement, (mode, delay) → Result<f32 lx>; Mode::H 1 lx, Mode::H2 0.5 lx, Mode::L 4 lx and 24 ms
    println!("H2 {:.2} lx", chip.measure_once(Mode::H2, &mut delay).expect("h2"));
    // Maximum measurement time, (mode) → f32 s
    println!("measurement time {:.3} s", chip.measurement_time(Mode::H));
    // Lux per raw count, (mode) → f32
    println!("lux per count {:.4}", chip.lux_per_count(Mode::H));

    // Start continuous mode, (mode) → Result<()>; first result after measurement_time()
    chip.start_continuous(Mode::H).expect("continuous");
    sleep_ms!(200);
    // Read the latest result, () → Result<f32 lx>; no wait
    println!("continuous {:.1} lx", chip.read_continuous().expect("continuous"));

    // Set MTreg, (mtreg 31-254) → Result<()>; default 69; larger = more sensitive and slower
    chip.set_measurement_time(138).expect("mtreg");
    // Get MTreg, () → u8; cached, the chip has no read-back
    println!("mtreg {}", chip.get_measurement_time_register());
    // Set sensitivity, (factor 0.45-3.68) → Result<()>; MTreg = round(69 x factor)
    chip.set_sensitivity(1.0).expect("sensitivity");

    // Power down, () → Result<()>
    chip.power_down().expect("power down");
    // Power on, () → Result<()>
    chip.power_on().expect("power on");
    // Clear the data register, () → Result<()>; powers on first; MTreg and mode are kept
    chip.reset().expect("reset");
}
