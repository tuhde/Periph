#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::light::Apds9930Full;

esp_app_desc!();

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());                     // Init ESP HAL, () → Peripherals
    let mut delay = Delay::new();                                                     // Create delay provider, () → Delay
    let i2c = I2c::new(peripherals.I2C0, Config::default())                           // Create I²C bus, (I2C0, config=default) → I2c
        .unwrap()
        .with_sda(peripherals.GPIO8)
        .with_scl(peripherals.GPIO9);
    let mut chip = Apds9930Full::new(i2c, 0x39, &mut delay).unwrap();                 // Construct APDS-9930 Full, (i2c, addr=0x39, delay) → Result<Apds9930Full, _>
                                                                                       // exposes ALS and proximity configuration methods

    chip.configure_als(0xDB, 0, false).unwrap();                                      // Configure ALS, (atime=0xDB, again=0, agl=false) → Result<(), _>
    chip.configure_proximity(8, 0, 0, false, 0xFF).unwrap();                          // Configure proximity, (ppulse=8, pgain=0, pdrive=0, pdl=false, ptime=0xFF) → Result<(), _>
    chip.disable_wait().unwrap();                                                     // Disable wait timer, () → Result<(), _>
    chip.set_als_thresholds(100, 60000, 1).unwrap();                                  // Set ALS thresholds, (low=100, high=60000, persistence=1) → Result<(), _>
    chip.set_proximity_thresholds(10, 200, 1).unwrap();                               // Set proximity thresholds, (low=10, high=200, persistence=1) → Result<(), _>
    chip.set_proximity_offset(0).unwrap();                                            // Set proximity offset, (offset=0) → Result<(), _>
    chip.sleep_after_interrupt(false).unwrap();                                       // Configure SAI, (enable=false) → Result<(), _>

    loop {
        let lx = chip.lux().unwrap();                                                  // Read ambient illuminance, () → Result<f32, _> lx
        let p  = chip.proximity().unwrap();                                            // Read proximity count, () → Result<u16, _> count
        let c0 = chip.ch0().unwrap();                                                  // Read Ch0 raw, () → Result<u16, _> count
        let c1 = chip.ch1().unwrap();                                                  // Read Ch1 raw, () → Result<u16, _> count
        let st = chip.status().unwrap();                                               // Read STATUS decoded, () → Result<Status, _>
        println!("lux={:.1} lx  prox={}  ch0={}  ch1={}  status={:?}", lx, p, c0, c1, st);
        chip.clear_interrupt(0).unwrap();                                              // Clear interrupts, (channel=0) → Result<(), _>
        delay.delay_ms(1000);
    }
}