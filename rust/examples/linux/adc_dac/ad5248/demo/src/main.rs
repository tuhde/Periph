use linux_embedded_hal::I2cdev;
use periph::chips::adc_dac::Ad5248Full;
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x2C);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut pot = Ad5248Full::new(dev, addr, 10000.0, 160.0);      // Create AD5248 driver, (i2c, addr, r_ab 10000 Ω, r_wiper 160 Ω) → Ad5248Full

    // --- Sweep channel 0 across its full range ---
    // Programmable gain / level trim: step the wiper from code 0 to 255 in steps of 16
    // and print the resulting W-B resistance. Zero scale is not 0 Ω (about 2*RW = 320 Ω).
    for code in (0..=255).step_by(16) {
        pot.set_wiper(0, code).unwrap();                            // Set channel 0 wiper, (channel 0-1, code 0-255) → ()
        let ohms = pot.get_resistance(0).unwrap();                  // Read W-B resistance, (channel 0-1) → f32 Ω
        println!("code={:3}  R_WB={:7.1} ohm", code, ohms);
        sleep(Duration::from_millis(100));
    }

    // --- Set channel 1 to 2.2 kΩ and report the quantisation error ---
    // 256 positions over 10 kΩ give a step of about 39 Ω, so the result is the nearest code.
    pot.set_resistance(1, 2200.0).unwrap();                         // Set channel 1 to 2.2 kΩ, (channel 0-1, ohms) → ()
    let actual = pot.get_resistance(1).unwrap();                    // Read back W-B resistance, (channel 0-1) → f32 Ω
    println!("ch1 target=2200 ohm  actual={:.1} ohm  error={:.1} ohm", actual, actual - 2200.0);

    // --- Shutdown and resume channel 1 ---
    // Shutdown opens terminal A and shorts the wiper to B (about 0 Ω) without changing the RDAC.
    pot.shutdown(1).unwrap();                                       // Shut channel 1 down, (channel 0-1) → ()
    println!("ch1 shutdown={}  code={}", pot.is_shutdown(1), pot.get_wiper(1).unwrap());  // Query state, (channel 0-1) → bool
    sleep(Duration::from_secs(1));
    pot.resume(1).unwrap();                                         // Resume channel 1, (channel 0-1) → ()

    // --- Restore the power-up preset ---
    pot.reset().unwrap();                                           // Reset both channels to midscale, () → ()
    println!("after reset: ch0={} ch1={}", pot.get_wiper(0).unwrap(), pot.get_wiper(1).unwrap());
}
