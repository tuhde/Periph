//! Demo for the BH1750 — light meter: all modes, continuous mode, sensitivity, reset and power cycle.

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

    // --- Light meter ---
    // One reading per mode, ten continuous values per second for 5 s (cover and
    // uncover the sensor), the effect of the lowest and highest sensitivity on the
    // raw count and the measurement time, reset and a power cycle.
    for (name, mode) in [("H (1 lx)", Mode::H), ("H2 (0.5 lx)", Mode::H2), ("L (4 lx)", Mode::L)] {
        // One-time measurement, (mode, delay) → Result<f32 lx>
        let lux = chip.measure_once(mode, &mut delay).expect("measure");
        println!("{:<12} {:9.2} lx", name, lux);
    }

    // Start continuous mode, (mode) → Result<()>
    chip.start_continuous(Mode::H).expect("continuous");
    for _ in 0..50 {
        sleep_ms!(100);
        // Read the latest result, () → Result<f32 lx>
        println!("{:.1} lx", chip.read_continuous().expect("continuous"));
    }

    for factor in [0.45f32, 3.68] {
        // Set sensitivity, (factor) → Result<()>
        chip.set_sensitivity(factor).expect("sensitivity");
        let lux = chip.measure_once(Mode::H, &mut delay).expect("measure");
        println!("x{}: MTreg {}, raw {}, {:.2} lx, max {:.0} ms", factor, chip.get_measurement_time_register(),
                 chip.read_raw(&mut delay).expect("raw"), lux, chip.measurement_time(Mode::H) * 1000.0);
    }
    chip.set_sensitivity(1.0).expect("sensitivity");

    // Clear the data register, () → Result<()>
    chip.reset().expect("reset");
    // Power down, () → Result<()>
    chip.power_down().expect("power down");
    // Power on, () → Result<()>
    chip.power_on().expect("power on");
}
