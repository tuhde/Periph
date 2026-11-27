//! Minimal example for the RRH46410 — print the air quality every sample. (ESP32-S3: SDA GPIO1, SCL GPIO2).
#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
#[allow(unused_imports)]
use esp_println::{print, println};
use periph::chips::gas::Rrh46410Minimal;

esp_app_desc!();

const ADDR: u8 = 0x38;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let bus = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    #[allow(unused_mut)]
    let mut delay = Delay::new();
    #[allow(unused_macros)]
    macro_rules! sleep_ms { ($n:expr) => { delay.delay_ms($n) }; }
    let addr = ADDR;

    // Create RRH46410 driver, (i2c, addr=0x38) → Rrh46410Minimal
    let mut chip = Rrh46410Minimal::new(bus, addr);
    // Check product ID, start IAQ 2nd Gen mode, (delay) → Result<()>
    chip.init(&mut delay).expect("init");

    loop {
        // Wait for the next sample, (delay) → Result<Measurement {iaq, tvoc_mg_m3, etoh_ppm, eco2_ppm, relative_iaq, sample, warmed_up}>
        match chip.read(&mut delay) {
            Ok(m) => println!("IAQ {:.1}  TVOC {:.2} mg/m3  EtOH {:.2} ppm  eCO2 {} ppm  {}", m.iaq, m.tvoc_mg_m3,
                              m.etoh_ppm, m.eco2_ppm, if m.warmed_up { "ok" } else { "warming up" }),
            Err(e) => println!("read failed: {:?}", e),
        }
    }
}
