#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::other::Mpr121Full;

esp_app_desc!();

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());                     // Init ESP HAL, () → Peripherals
    let mut delay = Delay::new();                                                     // Create delay provider, () → Delay
    let i2c = I2c::new(peripherals.I2C0, Config::default())                           // Create I²C bus, (I2C0, config=default 100kHz) → I2c
        .unwrap()
        .with_sda(peripherals.GPIO8)
        .with_scl(peripherals.GPIO9);

    let mut chip = Mpr121Full::new(i2c, 0x5A, &mut delay).unwrap();                  // Construct MPR121 Full, (i2c, addr=0x5A, delay) → Result<Mpr121Full, _>
    chip.stop().unwrap();                                                             // Enter Stop Mode, () → Result<(), _>
    chip.configure_thresholds(0, 15, 8).unwrap();                                     // Set thresholds, (electrode=0, touch=15, release=8) → Result<(), _>
    chip.configure_all_thresholds(12, 6).unwrap();                                    // Apply thresholds to all, (touch=12, release=6) → Result<(), _>
    chip.configure_proximity_thresholds(8, 4).unwrap();                               // Set ELEPROX thresholds, (touch=8, release=4) → Result<(), _>
    chip.configure_baseline_filter(1, 1, 0, 0, 1, 1, 0, 0, 1, 0, 0).unwrap();         // Set baseline filter, (mhdr, nhdr, nclr, fdlr, mhdf, nhdf, nclf, fdlf, nhdt, nclt, fdlt) → Result<(), _>
    chip.configure_sampling(16, 1, 0, 0, 4).unwrap();                                 // Set AFE config, (cdc=16, cdt=1, ffi=0, sfi=0, esi=4) → Result<(), _>
    chip.configure_debounce(1, 1).unwrap();                                           // Set debounce, (touch=1, release=1) → Result<(), _>
    chip.configure_autoconfig(3300, 0, false, true, true).unwrap();                   // Configure autoconfig, (vdd_mv=3300, retry=0, scts=false, are=true, ace=true) → Result<(), _>
    chip.start(12, 2, 0).unwrap();                                                    // Enter Run Mode, (n_electrodes=12, cl=2, eleprox_en=0) → Result<(), _>

    for _ in 0..10 {
        delay.delay_ms(200);
        let t = chip.touched().unwrap();                                               // Read 12-bit touch bitmask, () → Result<u16, _> bitmask
        let f0 = chip.filtered(0).unwrap();                                            // Read ELE0 filtered, (electrode=0) → Result<u16, _> 0..1023
        let b0 = chip.baseline(0).unwrap();                                            // Read ELE0 baseline, (electrode=0) → Result<u16, _> 0..1023
        let oor = chip.oor_status().unwrap();                                          // Read OOR bitmask, () → Result<u16, _> bitmask
        let pt = chip.proximity_touched().unwrap();                                    // Read proximity touched, () → Result<bool, _>
        println!("t=0x{:03X} f0={} b0={} oor=0x{:04X} pt={}", t, f0, b0, oor, pt);
    }
    chip.enable_interrupt(Mpr121Full::SOURCE_OOR).unwrap();                           // Enable interrupt source, (source=SOURCE_OOR) → Result<(), _>
    chip.disable_interrupt(Mpr121Full::SOURCE_OOR).unwrap();                          // Disable interrupt source, (source=SOURCE_OOR) → Result<(), _>
    chip.clear_overcurrent().unwrap();                                                // Clear OVCF, () → Result<(), _>
    chip.reset(&mut delay).unwrap();                                                  // Soft reset, (delay) → Result<(), _>
    loop { delay.delay_ms(1000); }
}
