use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::adc_dac::{Mcp4xxxFull, Mcp4xxxPart};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x2C);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    // MCP4661: dual 8-bit potentiometer with EEPROM (the non-volatile calls need such a part).
    let mut pot = Mcp4xxxFull::new(dev, addr, Mcp4xxxPart::Mcp4661, 10000.0, 75.0);  // Create MCP4XXX driver, (i2c, addr, part, r_ab 10000 Ω, r_wiper 75 Ω) → Mcp4xxxFull
    let mut delay = Delay;

    pot.init().unwrap();                                            // Bus check, () → ()
                                                                    // reads wipers, TCON and STATUS, no writes

    // --- Part properties ---
    println!("{}", pot.channels());                                 // Number of networks, () → u8 1-2
                                                                    // property of the selected part
    println!("{}", pot.steps());                                    // Number of wiper positions, () → u16 129 or 257
                                                                    // max code + 1
    println!("{}", pot.is_potentiometer());                         // Terminal A on a pin, () → bool
                                                                    // true for the X1 parts
    println!("{}", pot.is_nonvolatile());                           // Has EEPROM, () → bool
                                                                    // true for 454X / 456X / 464X / 466X

    // --- Wiper position and resistance ---
    pot.set_wiper(100, 0).unwrap();                                 // Set wiper, (code 0-256, channel) → ()
                                                                    // writes [(0 << 4) | D8, code & 0xFF]; code clamped
    println!("{}", pot.get_wiper(0).unwrap());                      // Read wiper, (channel) → u16 0-256
                                                                    // two-byte read of the wiper register
    pot.set_both(10, 20).unwrap();                                  // Set both wipers, (code0, code1) → ()
                                                                    // dual parts only; two transactions
    pot.set_resistance(2200.0, 1).unwrap();                         // Set W-B resistance, (ohms, channel) → ()
                                                                    // nearest code of R = N/max*RAB + RW, clamped
    println!("{}", pot.get_resistance(1).unwrap());                 // Read W-B resistance, (channel) → f32 Ω
                                                                    // N/max*RAB + RW
    println!("{}", pot.get_resistance_step());                      // Resistance of one step, () → f32 Ω
                                                                    // RAB / max code

    // --- Ratio and W-A resistance ---
    pot.set_ratio(0.5, 0).unwrap();                                 // Set wiper ratio, (ratio 0.0-1.0, channel) → ()
                                                                    // code = round(ratio * max code)
    println!("{}", pot.get_ratio(0).unwrap());                      // Read wiper ratio, (channel) → f32 0.0-1.0
                                                                    // code / max code
    pot.set_resistance_wa(3300.0, 0).unwrap();                      // Set W-A resistance, (ohms, channel) → ()
                                                                    // potentiometer parts only
    println!("{}", pot.get_resistance_wa(0).unwrap());              // Read W-A resistance, (channel) → f32 Ω
                                                                    // (max code - N)/max*RAB + RW

    // --- Increment / decrement ---
    println!("{}", pot.increment(8, 0).unwrap());                   // Step wiper up, (steps, channel) → u16
                                                                    // continuous transaction of increment commands, saturating
    println!("{}", pot.decrement(4, 0).unwrap());                   // Step wiper down, (steps, channel) → u16
                                                                    // same with decrement commands, saturating at 0

    // --- Shutdown and terminals ---
    pot.shutdown(1).unwrap();                                       // Shut network down, (channel) → ()
                                                                    // TCON.RxHW = 0: A open, W tied to B; wiper kept
    println!("{}", pot.is_shutdown(1).unwrap());                    // Read shutdown state, (channel) → bool
                                                                    // from TCON
    pot.resume(1).unwrap();                                         // Leave shutdown, (channel) → ()
                                                                    // TCON.RxHW = 1
    pot.set_terminals(true, false, true, 0).unwrap();               // Connect terminals, (a, w, b, channel) → ()
                                                                    // TCON.RxA / RxW / RxB, other bits preserved
    println!("{:?}", pot.get_terminals(0).unwrap());                // Read terminals, (channel) → (bool, bool, bool)
                                                                    // from TCON
    pot.set_terminals(true, true, true, 0).unwrap();                // Reconnect all terminals, (a, w, b, channel) → ()
                                                                    // restores the wiper terminal
    println!("{}", pot.get_tcon().unwrap());                        // Read TCON, () → u16 0-0x1FF
                                                                    // raw terminal control register
    pot.set_general_call(true).unwrap();                            // Set General Call, (enabled) → ()
                                                                    // TCON.GCEN; chip answers address 0x00 commands

    // --- Raw access and model ---
    println!("{}", pot.read_register(0x04).unwrap());               // Raw read, (address 0-15) → u16 0-0x1FF
                                                                    // nine-bit value of any address the part has
    pot.write_register(0x04, 0x1FF).unwrap();                       // Raw write, (address, value) → ()
                                                                    // volatile wipers, TCON, EEPROM addresses on NV parts
    pot.set_rab(10000.0, Some(75.0));                               // Set resistance model, (r_ab Ω, r_wiper Option Ω) → ()
                                                                    // no I²C traffic; affects conversions only

    // --- Non-volatile parts ---
    println!("{:?}", pot.read_status().unwrap());                   // Read STATUS, () → Status
                                                                    // EEPROM busy, WiperLock per network, write protect
    pot.wait_ready(&mut delay, 20).unwrap();                        // Wait for EEPROM write cycle, (delay, timeout_ms 20) → ()
                                                                    // polls STATUS.EEWA
    println!("{}", pot.get_nv_wiper(0).unwrap());                   // Read EEPROM wiper, (channel) → u16
                                                                    // power-up value
    pot.set_nv_wiper(&mut delay, 64, 0).unwrap();                   // Write EEPROM wiper, (delay, code, channel) → ()
                                                                    // volatile wiper unchanged; waits for the write cycle
    println!("{}", pot.store_wiper(&mut delay, 0).unwrap());        // Store current wiper, (delay, channel) → u16
                                                                    // reads the volatile wiper, writes it to EEPROM
    println!("{}", pot.read_data(0).unwrap());                      // Read EEPROM register, (index 0-9) → u16 0-0x1FF
                                                                    // general-purpose 9-bit register
    pot.write_data(&mut delay, 0, 0x155).unwrap();                  // Write EEPROM register, (delay, index 0-9, value 0-0x1FF) → ()
                                                                    // uses one of 1 000 000 write cycles
    println!("{}", pot.is_wiper_locked(0).unwrap());                // Read WiperLock, (channel) → bool
                                                                    // STATUS.WLx
    println!("{}", pot.is_write_protected().unwrap());              // Read write protect, () → bool
                                                                    // STATUS.WP

    pot.reset().unwrap();                                           // Emulate power-on reset, () → ()
                                                                    // EEPROM wiper (or mid-scale) in every wiper, TCON 0x1FF
}
