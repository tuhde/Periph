#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::environmental::{Bme688Full, OSRS_X2, OSRS_X16, OSRS_X1, MODE_SLEEP, FILTER_15};

esp_app_desc!();

const ADDR: u8 = 0x76;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();

    // --- Room air probe with parallel mode ---
    // Part 1: ten forced-mode readings (T, RH, hPa, Ohm, dew point), one per 5 s.
    // Part 2: parallel mode with a 3-step heater profile (320, 250 and 180 degC;
    // repeats 1, 2, 1; shared wait 100 ms) for one minute, printing the gas
    // resistance of every heater step, then back to sleep.
    let mut bme = Bme688Full::new(i2c, ADDR).expect("init BME688"); // Create BME688 driver, (i2c, addr)
    bme.configure(OSRS_X2, OSRS_X16, OSRS_X1, MODE_SLEEP, FILTER_15).expect("configure"); // Configure chip, (osrs_t=×2, osrs_p=×16, osrs_h=×1, mode=sleep, filter=15) → ()
    bme.set_heater(320, 150).expect("set_heater");                 // Configure heater profile 0, (temp_c=320, duration_ms=150) → ()

    println!("--- forced mode ---");
    for n in 0..10u32 {
        let (t, p, h, g) = bme.read_all().expect("read_all");      // Read all sensors in one cycle, () → (f32, f32, f32, f32)
        let gm = libm::logf(if h > 0.01 { h } else { 0.01 } / 100.0) + 17.62 * t / (243.12 + t);
        let dew = 243.12 * gm / (17.62 - gm);                      // Magnus dew point, °C
        println!("{}: {:.1} C, {:.1} %RH, {:.1} hPa, {:.0} Ohm, dew {:.1} C", n, t, h, p, g, dew);
        delay.delay_ms(5000);
    }

    println!("--- parallel mode ---");
    bme.start_parallel(&[(320, 1), (250, 2), (180, 1)], 100.0).expect("start_parallel"); // Start parallel mode, (steps=[(temp_c, repeats)], shared_wait_ms) → ()
    for _ in 0..60u32 {
        let fields = bme.read_parallel().expect("read_parallel");  // Read the new data fields, () → [Option<Bme688Field>; 3]
        for f in fields.iter().flatten() {
            println!("step {} #{}: {:.1} C, {:.1} %RH, {:.0} Ohm (valid={}, stable={})",
                     f.step, f.sub_index, f.temperature, f.humidity, f.gas_resistance, f.gas_valid, f.heater_stable);
        }
        delay.delay_ms(1000);
    }
    bme.stop_parallel().expect("stop_parallel");                   // Back to sleep, () → ()
    loop {}
}
