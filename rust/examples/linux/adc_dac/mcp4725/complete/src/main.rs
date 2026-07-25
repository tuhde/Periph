use linux_embedded_hal::I2cdev;
use periph::chips::adc_dac::Mcp4725Full;
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x60);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut dac = Mcp4725Full::new(dev, addr).expect("init MCP4725");

    loop {
        dac.set_voltage(0.75).expect("set voltage 0.75");         // Set output as fraction of V_DD, (fraction=0.0–1.0) → Result<(), E>
                                                                   // converts fraction to 12-bit code and issues Fast Write
        dac.set_raw(3000).expect("set raw 3000");                  // Set raw 12-bit code, (code=0–4095) → Result<(), E>
                                                                   // clamps to [0, 4095] and writes DAC register only
        dac.set_voltage_eeprom(0.5).expect("set voltage eeprom");  // Set output and persist to EEPROM, (fraction=0.0–1.0) → Result<(), E>
                                                                   // writes both DAC register and EEPROM for power-cycle persistence
        dac.set_raw_eeprom(2048).expect("set raw eeprom");         // Set raw code and persist to EEPROM, (code=0–4095) → Result<(), E>
                                                                   // writes both DAC register and EEPROM for power-cycle persistence
        let (code, vf, pd, ec, epd, ready) = dac.read().expect("read");  // Read DAC and EEPROM registers, () → (code, voltage_fraction, power_down, eeprom_code, eeprom_power_down, eeprom_ready)
        println!("code={} vf={:.4} pd={} ec={} epd={} ready={}", code, vf, pd, ec, epd, ready);
                                                                   // returns code, voltage_fraction, power_down, eeprom_code, eeprom_power_down, eeprom_ready
        dac.set_power_down(2).expect("set power down 100k");       // Set power-down mode with code preserved, (mode=0–3) → Result<(), E>
                                                                   // enters power-down; output stage disconnects with 100k to GND
        dac.wake_up().expect("wake up");                           // Send General Call Wake-Up to clear power-down, () → Result<(), E>
                                                                   // sends 0x00, 0x09 to address 0x00; clears PD bits in DAC register
        dac.reset().expect("reset");                               // Send General Call Reset and reload EEPROM, () → Result<(), E>
                                                                   // sends 0x00, 0x06; triggers internal POR and reloads DAC from EEPROM
        let ready = dac.is_eeprom_ready().expect("eeprom ready");  // Check if EEPROM write is complete, () → Result<bool, E>
                                                                   // returns True when any pending EEPROM write has finished
        println!("eeprom_ready={}", ready);
        sleep(Duration::from_secs(1));
    }
}