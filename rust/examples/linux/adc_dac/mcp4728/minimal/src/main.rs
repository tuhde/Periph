use linux_embedded_hal::I2cdev;
use periph::chips::adc_dac::Mcp4728Minimal;
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x60);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut dac = Mcp4728Minimal::new(dev, addr).expect("init MCP4728");

    loop {
        dac.set_voltage(0, 0.5).expect("set voltage ch0");   // Set channel A as fraction of V_DD, (channel=0–3, fraction=0.0–1.0) → Result<(), E>
        dac.set_raw(1, 2048).expect("set raw ch1");          // Set channel B raw 12-bit code, (channel=0–3, code=0–4095) → Result<(), E>
        dac.set_all([0.0, 0.25, 0.5, 1.0]).expect("set all"); // Update all four channels simultaneously, (fractions[4]) → Result<(), E>
        println!("MCP4728 minimal running");
        sleep(Duration::from_secs(1));
    }
}
