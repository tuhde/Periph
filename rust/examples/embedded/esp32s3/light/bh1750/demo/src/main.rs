//! Demo for the BH1750 — light meter: all modes, continuous mode, sensitivity, reset and power cycle. (ESP32-S3: SDA GPIO1, SCL GPIO2).
#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::light::{BH1750Full, BH1750Mode as Mode};

esp_app_desc!();

const ADDR: u8 = 0x23;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    #[allow(unused_mut)]
    let mut delay = Delay::new();
    #[allow(unused_macros)]
    macro_rules! sleep_ms { ($n:expr) => { delay.delay_ms($n) }; }

    // Create BH1750 driver, (i2c, addr=0x23) → BH1750
    let mut chip = BH1750Full::new(i2c, ADDR);
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

    loop {
        sleep_ms!(1000);
    }
}
