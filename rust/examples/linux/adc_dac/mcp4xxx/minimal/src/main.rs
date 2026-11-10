use linux_embedded_hal::I2cdev;
use periph::chips::adc_dac::{Mcp4xxxMinimal, Mcp4xxxPart};
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x58);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut pot = Mcp4xxxMinimal::new(dev, addr, Mcp4xxxPart::Mcp4532, 10000.0, 75.0);  // Create MCP4XXX driver, (i2c, addr, part, r_ab 10000 Ω, r_wiper 75 Ω) → Mcp4xxxMinimal

    pot.init().unwrap();                                            // Bus check, () → ()
    pot.set_wiper(32, 0).unwrap();                                  // Set wiper, (code 0-128, channel) → ()
    pot.set_resistance(2200.0, 0).unwrap();                         // Set W-B resistance, (ohms, channel) → ()

    loop {
        let code = pot.get_wiper(0).unwrap();                       // Read wiper, (channel) → u16 0-128
        let ohms = pot.get_resistance(0).unwrap();                  // Read W-B resistance, (channel) → f32 Ω
        println!("code={}  R_WB={:.0} ohm", code, ohms);
        sleep(Duration::from_secs(1));
    }
}
