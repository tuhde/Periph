#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::humidity::{Repeatability, Sht31Full};

esp_app_desc!();

const ADDR: u8 = 0x44;

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

    // Create SHT31 driver, (i2c, ADDR=0x44, delay) → Result
    let mut chip = Sht31Full::new(i2c, ADDR, &mut delay).expect("init");

    // Soft reset, (delay) → Result<()>
    chip.soft_reset(&mut delay).expect("soft_reset");
    // Read status register, () → Result<u16>
    println!("status 0x{:04X}", chip.read_status().expect("status"));

    // --- Single shots at all three repeatability levels ---
    for level in [Repeatability::Low, Repeatability::Medium, Repeatability::High] {
        // Select repeatability, (level) → ()
        chip.set_repeatability(level);
        // Read temperature and humidity, (delay) → Result<(f32, f32)>
        let (t, rh) = chip.read(&mut delay).expect("read");
        println!("{:?}: {:.2} C {:.1} %RH", level, t, rh);
    }
    // Use clock-stretching commands, (on) → ()
    chip.set_clock_stretching(false);
    // Read raw words, (delay) → Result<(u16, u16)>
    println!("raw {:?}", chip.read_raw(&mut delay).expect("raw"));

    // --- Periodic mode at 2 mps: ten samples ---
    // Start periodic mode, (rate, repeatability, art) → Result<()>
    chip.start_periodic(2.0, Repeatability::High, false).expect("start_periodic");
    let mut got = 0;
    while got < 10 {
        // Fetch the latest periodic result, () → Result<Option<(f32, f32)>>
        match chip.fetch().expect("fetch") {
            Some((t, rh)) => {
                got += 1;
                println!("periodic {}: {:.2} C {:.1} %RH", got, t, rh);
            }
            None => sleep_ms!(50),
        }
    }
    // Stop periodic mode, (delay) → Result<()>
    chip.stop_periodic(&mut delay).expect("stop_periodic");

    // --- Heater test: 10 s ---
    // Read temperature only, (delay) → Result<f32>
    let before = chip.read_temperature(&mut delay).expect("temperature");
    // Switch the heater, (on) → Result<()>
    chip.set_heater(true).expect("heater on");
    sleep_ms!(10000);
    println!("heater status bit: {}", chip.read_status().expect("status") & 0x2000 != 0);
    let after = chip.read_temperature(&mut delay).expect("temperature");
    println!("temperature rise: {:.2} K", after - before);
    chip.set_heater(false).expect("heater off");
    // Clear status flags, () → Result<()>
    chip.clear_status().expect("clear_status");
    println!("status 0x{:04X}", chip.read_status().expect("status"));
    // Compute dew point, (delay) → Result<f32> °C
    println!("dew point {:.2} C", chip.dew_point(&mut delay).expect("dew_point"));

    loop {
        delay.delay_ms(1000);
    }
}
