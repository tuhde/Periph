//! Demo for the SHTC3 — battery-friendly climate logger with a normal vs low-power comparison and the dew point. (ESP32-S3: SDA GPIO1, SCL GPIO2).
#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::humidity::SHTC3Full;

esp_app_desc!();

const ADDR: u8 = 0x70;

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

    // Create SHTC3 driver, (i2c, addr=0x70) → SHTC3
    let mut chip = SHTC3Full::new(i2c, ADDR);
    // Soft reset and ID check, (delay) → Result<()>
    chip.init(&mut delay).expect("init");

    // --- Identify the chip ---
    // The ID word carries the SHTC3 product code in bits 11 and 5:0.
    // Read the ID word, (delay) → Result<u16>
    println!("id 0x{:04X}", chip.read_id(&mut delay).expect("id"));

    // --- Battery-friendly climate logger: 6 readings, 10 s apart ---
    // The sensor sleeps (0.3 uA) between the readings; every read wakes it.
    for _ in 0..6 {
        // Read temperature and humidity, (delay) → Result<(f32 °C, f32 %RH)>
        let (t, rh) = chip.read(&mut delay).expect("read");
        println!("{:.2} C  {:.1} %RH", t, rh);
        sleep_ms!(10000);
    }

    // --- Compare normal and low-power mode ---
    // Low power measures in 0.7 ms instead of 10.8 ms at slightly higher noise.
    // Select low-power mode, (on) → ()
    println!("normal    {:?}", chip.read(&mut delay).expect("read"));
    chip.set_low_power(true);
    println!("low power {:?}", chip.read(&mut delay).expect("read"));
    chip.set_low_power(false);

    // --- Dew point and raw words, then finish asleep ---
    // Compute dew point, (delay) → Result<f32 °C>
    println!("dew point {:.2} C", chip.dew_point(&mut delay).expect("dew"));
    // Read raw codes, (delay) → Result<(u16, u16)>
    println!("raw {:?}", chip.read_raw(&mut delay).expect("raw"));
    // Sleep command, () → Result<()>
    chip.sleep().expect("sleep");

    loop {
        sleep_ms!(1000);
    }
}
