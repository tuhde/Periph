use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::light::Apds9930Full;
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x39);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus"); // Open I²C bus, (/dev/i2c-N) → I2cdev
    let mut delay = Delay;
    let mut chip = Apds9930Full::new(dev, addr, &mut delay).expect("init");       // Construct APDS-9930 Full, (i2c, addr=0x39, delay) → Result<Apds9930Full, _>
                                                                                    // exposes ALS and proximity configuration methods

    sleep(Duration::from_millis(110));

    chip.configure_als(0xDB, 0, false).expect("configure_als");                      // Configure ALS, (atime=0xDB, again=0, agl=false) → Result<(), _>
                                                                                    // sets ALS integration time to 101 ms with 1x gain
    chip.configure_proximity(8, 0, 0, false, 0xFF).expect("configure_proximity");    // Configure proximity, (ppulse=8, pgain=0, pdrive=0, pdl=false, ptime=0xFF) → Result<(), _>
                                                                                    // 8 LED pulses at 100 mA, 1x gain, no reduced drive
    chip.disable_wait().expect("disable_wait");                                      // Disable wait timer, () → Result<(), _>
                                                                                    // clears WEN in ENABLE
    chip.set_als_thresholds(100, 60000, 1).expect("als thresholds");                 // Set ALS thresholds, (low=100, high=60000, persistence=1) → Result<(), _>
                                                                                    // fires after 1 consecutive out-of-range Ch0 count
    chip.set_proximity_thresholds(10, 200, 1).expect("prox thresholds");             // Set proximity thresholds, (low=10, high=200, persistence=1) → Result<(), _>
                                                                                    // fires on a single proximity reading outside [10, 200]
    chip.set_proximity_offset(0).expect("offset");                                   // Set proximity offset, (offset=0) → Result<(), _>
                                                                                    // clears any prior offset
    chip.sleep_after_interrupt(false).expect("sai");                                 // Configure SAI, (enable=false) → Result<(), _>
                                                                                    // chip stays in normal operation after an interrupt

    for _ in 0..10 {
        sleep(Duration::from_millis(110));
        let lx = chip.lux().expect("lux");                                           // Read ambient illuminance, () → Result<f32, _> lx
                                                                                    // combines Ch0 and Ch1 with IR-compensation coefficients
        let p  = chip.proximity().expect("proximity");                               // Read proximity count, () → Result<u16, _> count
                                                                                    // 16-bit ADC value
        let c0 = chip.ch0().expect("ch0");                                           // Read Ch0 raw, () → Result<u16, _> count
                                                                                    // 16-bit ADC value of visible + IR channel
        let c1 = chip.ch1().expect("ch1");                                           // Read Ch1 raw, () → Result<u16, _> count
                                                                                    // 16-bit ADC value of IR-only channel
        let st = chip.status().expect("status");                                     // Read STATUS decoded, () → Result<Status, _>
                                                                                    // {avalid, pvalid, psat, aint, pint} booleans
        println!("lux={:.1} lx  prox={}  ch0={}  ch1={}  status={:?}", lx, p, c0, c1, st);
    }
    chip.clear_interrupt(0).expect("clear_interrupt");                               // Clear interrupts, (channel=0) → Result<(), _>
                                                                                    // 0=both, 1=ALS, 2=proximity — issues special-function command
}