use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::adc_dac::{Mcp4xxxFull, Mcp4xxxPart};
use std::thread::sleep;
use std::time::Duration;

fn main() {
    // PART selects any of MCP4531 ... MCP4662; the default address is that part's first address.
    let part = std::env::var("MCP4XXX_PART").ok().and_then(|n| Mcp4xxxPart::from_name(&n)).unwrap_or(Mcp4xxxPart::Mcp4532);
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(part.default_address());

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut pot = Mcp4xxxFull::new(dev, addr, part, 10000.0, 75.0);  // Create MCP4XXX driver, (i2c, addr, part, r_ab 10000 Ω, r_wiper 75 Ω) → Mcp4xxxFull
    let mut delay = Delay;
    pot.init().unwrap();                                            // Bus check, () → ()

    // --- Identify the family member ---
    // One driver covers sixteen parts; the descriptor tells resolution, networks and memory type.
    println!("{} network(s), {} steps, {}, {}", pot.channels(), pot.steps(),   // Part properties, () → u8 / u16
             if pot.is_potentiometer() { "pot" } else { "rheostat" },
             if pot.is_nonvolatile() { "EEPROM" } else { "RAM" });
    println!("power-up wiper {} {} ohm", pot.get_wiper(0).unwrap(), pot.get_resistance(0).unwrap());  // Read wiper, (channel) → u16

    // --- Set 2.2 kΩ and report the quantisation error ---
    // The wiper snaps to the nearest step of RAB / max code.
    pot.set_resistance(2200.0, 0).unwrap();                         // Set W-B resistance, (ohms, channel) → ()
    let actual = pot.get_resistance(0).unwrap();                    // Read W-B resistance, (channel) → f32 Ω
    println!("target=2200 ohm  code={}  actual={:.1} ohm  error={:.1} ohm", pot.get_wiper(0).unwrap(), actual, actual - 2200.0);

    // --- Step the wiper up and down ---
    // increment / decrement send one command byte per step in one transaction and saturate at
    // full scale and zero scale.
    for (steps, up) in [(8u32, true), (8, true), (4, false), (10, true)] {
        let code = if up { pot.increment(steps, 0) } else { pot.decrement(steps, 0) }.unwrap();  // Step wiper, (steps, channel) → u16
        println!("{}{} -> code={}  R_WB={:.1} ohm", if up { '+' } else { '-' }, steps, code, pot.get_resistance(0).unwrap());
    }

    // --- Ratios ---
    // The ratio is the fraction of RAB between W and B (the unloaded divider ratio of a potentiometer).
    for ratio in [0.0f32, 0.25, 0.5, 0.75, 1.0] {
        pot.set_ratio(ratio, 0).unwrap();                           // Set wiper ratio, (ratio 0.0-1.0, channel) → ()
        print!("ratio={:.2}  code={}  R_WB={:.1}", ratio, pot.get_wiper(0).unwrap(), pot.get_resistance(0).unwrap());
        if pot.is_potentiometer() {
            print!("  R_WA={:.1}", pot.get_resistance_wa(0).unwrap());  // Read W-A resistance, (channel) → f32 Ω
        }
        println!();
    }

    // --- Dual parts: drive both networks ---
    if pot.channels() == 2 {
        pot.set_both(32, 96).unwrap();                              // Set both wipers, (code0, code1) → ()
        println!("both: {} {} -> {} {}", pot.get_wiper(0).unwrap(), pot.get_wiper(1).unwrap(),
                 pot.increment(5, 1).unwrap(), pot.decrement(2, 1).unwrap());
    }

    // --- Shutdown and terminal disconnect ---
    // Shutdown ties W to B and opens A without losing the wiper; set_terminals(w = false) floats W.
    pot.shutdown(0).unwrap();                                       // Shut network down, (channel) → ()
    println!("shutdown={}  code={}", pot.is_shutdown(0).unwrap(), pot.get_wiper(0).unwrap());  // Read state, (channel) → bool
    sleep(Duration::from_secs(1));
    pot.resume(0).unwrap();                                         // Leave shutdown, (channel) → ()
    pot.set_terminals(true, false, true, 0).unwrap();               // Disconnect the wiper, (a, w, b, channel) → ()
    println!("terminals {:?}", pot.get_terminals(0).unwrap());      // Read terminals, (channel) → (bool, bool, bool)
    pot.set_terminals(true, true, true, 0).unwrap();                // Reconnect all terminals, (a, w, b, channel) → ()

    // --- Non-volatile parts: EEPROM wiper and data ---
    // store_wiper makes the setting survive power cycles; every EEPROM write uses one of the
    // 1 000 000 write cycles, so do not do this in a loop.
    if pot.is_nonvolatile() {
        println!("{:?}", pot.read_status().unwrap());               // Read STATUS, () → Status
        println!("stored {}", pot.store_wiper(&mut delay, 0).unwrap());  // Store the wiper in EEPROM, (delay, channel) → u16
        pot.write_data(&mut delay, 0, 0x155).unwrap();              // Write calibration marker, (delay, index 0-9, value 0-0x1FF) → ()
        println!("data0 {:#x} nv wiper {}", pot.read_data(0).unwrap(), pot.get_nv_wiper(0).unwrap());  // Read back, (index) → u16
    }

    // --- Back to the power-up state ---
    pot.reset().unwrap();                                           // Emulate power-on reset, () → ()
    println!("after reset: {}", pot.get_wiper(0).unwrap());
}
