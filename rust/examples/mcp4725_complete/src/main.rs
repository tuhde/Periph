use linux_embedded_hal::I2cdev;
use periph::chips::adc_dac::Mcp4725Full;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x60);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut dac = Mcp4725Full::new(dev, addr);

    dac.set_voltage(0.5).unwrap();
    dac.set_raw(2048).unwrap();
    dac.set_voltage_eeprom(0.75).unwrap();
    dac.set_raw_eeprom(3000).unwrap();

    let result = dac.read().unwrap();
    println!("code: {}", result.code);
    println!("voltage_fraction: {:.4}", result.voltage_fraction);
    println!("power_down: {}", result.power_down);
    println!("eeprom_code: {}", result.eeprom_code);
    println!("eeprom_power_down: {}", result.eeprom_power_down);
    println!("eeprom_ready: {}", result.eeprom_ready);
    println!("por: {}", result.por);

    dac.set_power_down(1).unwrap();
    dac.wake_up().unwrap();
    dac.reset().unwrap();
    dac.is_eeprom_ready().unwrap();
}