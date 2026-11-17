#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::humidity::HDC1080Full;

esp_app_desc!();

const ADDR: u8 = 0x40;

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

    // Create HDC1080 driver, (i2c, addr=0x40) → HDC1080
    let mut chip = HDC1080Full::new(i2c, ADDR);

    // Write default configuration, () → Result<()>
    chip.init().expect("init");
    // Read the 40-bit serial ID, () → Result<u64>
    println!("serial id 0x{:010X}", chip.serial_id().expect("serial_id"));

    // --- Condensation-aware hygrometer: 15 readings, 2 s apart ---
    for _ in 0..15 {
        // Read temperature and humidity, (delay) → Result<(f32, f32)>
        let (t, rh) = chip.read(&mut delay).expect("read");
        let rh_c = if rh < 0.01 { 0.01 } else { rh };
        let g = libm_ln(rh_c / 100.0) + 17.62 * t / (243.12 + t);
        let dew = 243.12 * g / (17.62 - g); // Magnus dew point, °C
        println!("{:.2} C {:.1} %RH dew point {:.2} C", t, rh, dew);
        sleep_ms!(2000);
    }

    // --- Heater test: 10 s ---
    let rh0 = chip.read_humidity(&mut delay).expect("rh");
    let t0 = chip.read_temperature(&mut delay).expect("t");
    // Switch the heater, (on) → Result<()>
    chip.set_heater(true).expect("heater on");
    sleep_ms!(10000);
    let rh1 = chip.read_humidity(&mut delay).expect("rh");
    let t1 = chip.read_temperature(&mut delay).expect("t");
    println!("RH drop {:.1} %RH, temperature rise {:.2} K", rh0 - rh1, t1 - t0);
    chip.set_heater(false).expect("heater off");

    // --- Resolution comparison ---
    // Set resolution, (temperature_bits, humidity_bits) → Result<()>
    chip.set_resolution(11, 8).expect("set_resolution");
    let (t, rh) = chip.read(&mut delay).expect("read");
    println!("11/8 bit: {:.2} C {:.1} %RH (conversion {:.2} ms)", t, rh, chip.conversion_time() * 1000.0);
    chip.set_resolution(14, 14).expect("set_resolution");
    // Battery flag, () → Result<bool>
    println!("battery low {}", chip.battery_low().expect("battery_low"));

    loop {
        delay.delay_ms(1000);
    }
}

/// Natural logarithm without libm (no_std friendly series, accurate to a few 1e-5 for the demo's range).
fn libm_ln(x: f32) -> f32 {
    let mut m = x;
    let mut e = 0i32;
    while m > 1.5 {
        m /= 2.0;
        e += 1;
    }
    while m < 0.75 {
        m *= 2.0;
        e -= 1;
    }
    let y = (m - 1.0) / (m + 1.0);
    let y2 = y * y;
    2.0 * y * (1.0 + y2 / 3.0 + y2 * y2 / 5.0 + y2 * y2 * y2 / 7.0) + e as f32 * core::f32::consts::LN_2
}
