use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::other::{Mpr121Full, SOURCE_OOR};
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x5A);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus"); // Open I²C bus, (/dev/i2c-N) → I2cdev
    let mut delay = Delay;
    let mut chip = Mpr121Full::new(dev, addr, &mut delay).expect("init");         // Construct MPR121 Full, (i2c, addr=0x5A, delay) → Result<Mpr121Full, _>

    chip.stop().expect("stop");                                                    // Enter Stop Mode, () → Result<(), _>
    chip.configure_thresholds(0, 15, 8).expect("thresholds");                      // Set thresholds, (electrode=0, touch=15, release=8) → Result<(), _>
    chip.configure_all_thresholds(12, 6).expect("all thresholds");                 // Apply thresholds to all, (touch=12, release=6) → Result<(), _>
    chip.configure_proximity_thresholds(8, 4).expect("prox thresholds");           // Set ELEPROX thresholds, (touch=8, release=4) → Result<(), _>
    chip.configure_baseline_filter(1, 1, 0, 0, 1, 1, 0, 0, 1, 0, 0)               // Set baseline filter, (mhdr, nhdr, nclr, fdlr, mhdf, nhdf, nclf, fdlf, nhdt, nclt, fdlt) → Result<(), _>
        .expect("baseline filter");
    chip.configure_sampling(16, 1, 0, 0, 4).expect("sampling");                     // Set AFE config, (cdc=16, cdt=1, ffi=0, sfi=0, esi=4) → Result<(), _>
    chip.configure_debounce(1, 1).expect("debounce");                              // Set debounce, (touch=1, release=1) → Result<(), _>
    chip.configure_autoconfig(3300, 0, false, true, true)                          // Configure autoconfig, (vdd_mv=3300, retry=0, scts=false, are=true, ace=true) → Result<(), _>
        .expect("autoconfig");
    chip.start(12, 2, 0).expect("start");                                           // Enter Run Mode, (n_electrodes=12, cl=2, eleprox_en=0) → Result<(), _>

    for _ in 0..10 {
        sleep(Duration::from_millis(200));
        let t = chip.touched().expect("touched");                                   // Read 12-bit touch bitmask, () → Result<u16, _> bitmask
        let f0 = chip.filtered(0).expect("filtered");                               // Read ELE0 filtered, (electrode=0) → Result<u16, _> 0..1023
        let b0 = chip.baseline(0).expect("baseline");                               // Read ELE0 baseline, (electrode=0) → Result<u16, _> 0..1023
        let oor = chip.oor_status().expect("oor");                                  // Read OOR bitmask, () → Result<u16, _> bitmask
        let pt = chip.proximity_touched().expect("prox");                           // Read proximity touched, () → Result<bool, _>
        println!("t=0x{:03X} f0={} b0={} oor=0x{:04X} pt={}", t, f0, b0, oor, pt);
    }
    chip.enable_interrupt(SOURCE_OOR).expect("enable int");             // Enable interrupt source, (source=SOURCE_OOR) → Result<(), _>
    chip.disable_interrupt(SOURCE_OOR).expect("disable int");           // Disable interrupt source, (source=SOURCE_OOR) → Result<(), _>
    chip.clear_overcurrent().expect("clear ovrc");                                  // Clear OVCF, () → Result<(), _>
    chip.reset(&mut delay).expect("reset");                                         // Soft reset, (delay) → Result<(), _>
}
