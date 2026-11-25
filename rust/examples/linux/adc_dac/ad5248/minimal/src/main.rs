use linux_embedded_hal::I2cdev;
use periph::chips::adc_dac::Ad5248Minimal;
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x2C);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut pot = Ad5248Minimal::new(dev, addr, 10000.0, 160.0);   // Create AD5248 driver, (i2c, addr, r_ab 10000 Ω, r_wiper 160 Ω) → Ad5248Minimal

    pot.set_wiper(0, 64).unwrap();                                  // Set channel 0 wiper, (channel 0-1, code 0-255) → ()
    pot.set_resistance(1, 2200.0).unwrap();                         // Set channel 1 W-B resistance, (channel 0-1, ohms) → ()

    loop {
        let code = pot.get_wiper(0).unwrap();                       // Read channel 0 wiper, (channel 0-1) → u8 0-255
        let ohms = pot.get_resistance(0).unwrap();                  // Read channel 0 W-B resistance, (channel 0-1) → f32 Ω
        println!("ch0 code={}  R_WB={:.0} ohm", code, ohms);
        sleep(Duration::from_secs(1));
    }
}
