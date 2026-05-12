use linux_embedded_hal::I2cdev;
use periph::chips::adc_dac::Mcp4725Minimal;
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x60);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut dac = Mcp4725Minimal::new(dev, addr).expect("init MCP4725");

    loop {
        dac.set_voltage(0.5).expect("set voltage 0.5");  // Set output as fraction of V_DD, (fraction=0.0–1.0) → Result<(), E>
        dac.set_raw(2048).expect("set raw 2048");         // Set raw 12-bit code, (code=0–4095) → Result<(), E>
        println!("MCP4725 minimal running");
        sleep(Duration::from_secs(1));
    }
}