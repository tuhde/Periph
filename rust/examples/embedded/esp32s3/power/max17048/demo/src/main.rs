#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::power::Max17048Full;
use periph::chips::power::max17048::{SOURCE_RESET, SOURCE_SOC_CHANGE};

esp_app_desc!();

const ADDR: u8 = 0x36;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();

    let mut chip = Max17048Full::new(i2c, ADDR, 1).expect("init MAX17048");     // Create MAX17048 driver, (i2c, addr, cells)

    println!("version 0x{:04X}, id 0x{:02X}", chip.version().unwrap(), chip.chip_id().unwrap());
    chip.clear_status(SOURCE_RESET).unwrap();                           // Clear the reset indicator, (mask) → Result<()>

    // Battery fuel gauge: one line per second for 30 s
    for _ in 0..30 {
        println!("{:.3} V  {:.1} %  {:+.1} %/h", chip.voltage().unwrap(), chip.percent().unwrap(), chip.charge_rate().unwrap());
        delay.delay_ms(1000);
    }

    chip.set_alert_soc(10).unwrap();                                    // Low-SOC alert at 10 %, (percent) → Result<()>
    chip.set_voltage_alert(3.3, 4.25).unwrap();                         // Voltage window 3.3-4.25 V, (min_v, max_v) → Result<()>
    // No callbacks in Rust: poll the alert sources (or call this from the ALRT falling-edge ISR)
    println!("poll_interrupt: 0x{:04X}", chip.poll_interrupt().unwrap()); // () → Result<u16>

    for celsius in [5.0f32, 35.0, 20.0] {
        chip.compensate_temperature(celsius).unwrap();                  // RCOMP for the temperature, (celsius) → Result<()>
        println!("RCOMP at {} C: 0x{:02X}", celsius, chip.get_rcomp().unwrap());
    }

    chip.disable_hibernate().unwrap();                                  // Never hibernate, () → Result<()>
    println!("hibernating: {}", chip.is_hibernating());
    chip.set_hibernate_thresholds(26.624, 0.06).unwrap();               // Back to the defaults

    chip.enable_sleep(true).unwrap();                                   // Sleep round trip, (enabled) → Result<()>
    chip.sleep().unwrap();
    delay.delay_ms(1000);
    chip.wake().unwrap();
    chip.enable_sleep(false).unwrap();
    println!("after wake: {:.3} V", chip.voltage().unwrap());

    loop {}
}
