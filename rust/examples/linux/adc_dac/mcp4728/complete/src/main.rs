use linux_embedded_hal::I2cdev;
use periph::chips::adc_dac::Mcp4728Full;
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x60);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut dac = Mcp4728Full::new(dev, addr).expect("init MCP4728");

    loop {
        dac.set_voltage(0, 0.75).expect("set voltage 0.75");           // Set channel A as fraction of V_DD, (channel=0–3, fraction=0.0–1.0) → Result<(), E>
                                                                       // Multi-Write, V_REF=external, gain=×1, PD=00
        dac.set_raw(2, 3000).expect("set raw 3000");                    // Set channel C raw code, (channel=0–3, code=0–4095) → Result<(), E>
                                                                       // clamps to [0, 4095]; writes channel C only
        dac.set_all([0.1, 0.2, 0.3, 0.4]).expect("set all");           // Update all four channels simultaneously, (fractions[4]) → Result<(), E>
                                                                       // single 8-byte Fast Write transaction; EEPROM unaffected
        dac.set_voltage_eeprom(0, 0.5, 0, 1).expect("voltage eeprom");  // Set channel A and persist to EEPROM, (channel=0–3, fraction, vref=0/1, gain=1/2) → Result<(), E>
                                                                       // Single Write updates DAC register and nonvolatile EEPROM
        dac.set_raw_eeprom(1, 2048, 0, 1).expect("raw eeprom");         // Set channel B raw code and persist, (channel=0–3, code, vref=0/1, gain=1/2) → Result<(), E>
                                                                       // Single Write; takes up to 50 ms for EEPROM write
        dac.set_all_eeprom([0.0, 0.25, 0.5, 0.75],                     // Update all four channels + EEPROM, (fractions[4], vrefs[4], gains[4]) → Result<(), E>
                           [0, 0, 0, 0],                               // each channel uses external V_DD
                           [1, 1, 1, 1]).expect("set all eeprom");     // and gain ×1
                                                                       // Sequential Write from A to D; persists all four at the end
        dac.set_vref(0, 0, 0, 0).expect("set vref");                   // Set V_REF for all four channels, (vref_a, vref_b, vref_c, vref_d) → Result<(), E>
                                                                       // 0 = external V_DD; volatile register only
        dac.set_gain(1, 1, 1, 1).expect("set gain");                   // Set gain for all four channels, (gain_a, gain_b, gain_c, gain_d) → Result<(), E>
                                                                       // 1 = ×1, 2 = ×2; volatile register only
        dac.set_power_down(0, 0, 0, 0).expect("set power down");       // Set power-down for all four channels, (pd_a, pd_b, pd_c, pd_d) → Result<(), E>
                                                                       // 0 = normal, 1 = 1 kΩ, 2 = 100 kΩ, 3 = 500 kΩ to GND
        let state = dac.read().expect("read");                         // Read all four channels' DAC and EEPROM state, () → Result<ReadResult, E>
                                                                       // 4 ChannelState entries with code, vref, gain, power_down, eeprom_*
        println!("ch0 code={} eeprom_ready={}", state.channel[0].code, state.eeprom_ready);
        dac.software_update().expect("software update");               // Latch all V_OUT simultaneously, () → Result<(), E>
                                                                       // General Call 0x00, 0x08; equivalent to LDAC pin pulse
        dac.wake_up().expect("wake up");                               // Clear all PD bits via General Call Wake-Up, () → Result<(), E>
                                                                       // sends 0x00, 0x09; clears power-down on all four channels
        dac.reset().expect("reset");                                   // Reload EEPROM into all DAC registers, () → Result<(), E>
                                                                       // General Call 0x00, 0x06; triggers internal POR
        let ready = dac.is_eeprom_ready().expect("eeprom ready");      // Check if EEPROM write is complete, () → Result<bool, E>
                                                                       // True when any pending EEPROM write has finished
        println!("eeprom_ready={}", ready);
        sleep(Duration::from_secs(1));
    }
}
