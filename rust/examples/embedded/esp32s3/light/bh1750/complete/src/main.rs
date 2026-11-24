//! Complete example for the BH1750 — every BH1750Full method once. (ESP32-S3: SDA GPIO1, SCL GPIO2).
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

    loop {
        sleep_ms!(1000);
    }
}
