use linux_embedded_hal::I2cdev;
use periph::chips::pressure::{Lps33hwFull, ODR_10_HZ, FIFO_MODE_STREAM};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x5C);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut chip = Lps33hwFull::new(dev, addr).expect("init LPS33HW"); // Create LPS33HW driver, (i2c, addr=0x5C)
    let st = chip.status().expect("read status");                      // Read status register, () → u8
                                                                         // bit 0 = P_DA, bit 1 = T_DA, bit 4 = P_OR, bit 5 = T_OR
    let intsrc = chip.interrupt_status().expect("read int source");     // Read INT_SOURCE register, () → u8
                                                                         // bit 0 = PH, bit 1 = PL, bit 2 = IA, bit 7 = BOOT_STATUS
    chip.configure(ODR_10_HZ, true, true, 1, false, false).expect("configure");  // Configure chip, (odr 0–5, bdu bool, en_lpfp bool, lpfp_cfg 0/1, lc_en bool, sim bool) → ()
                                                                         // sets ODR/BDU/LPF/SIM in CTRL_REG1 and LC_EN bit in RES_CONF
    let (p, t) = chip.one_shot().expect("one_shot");                  // Trigger one-shot, () → (f32 Pa, f32 °C)
                                                                         // writes ONE_SHOT in CTRL_REG2, polls P_DA+T_DA, returns (Pa, °C)
    let p2 = chip.pressure().expect("read pressure");                  // Read pressure, () → f32 Pa
    let t2 = chip.temperature().expect("read temperature");            // Read temperature, () → f32 °C
    chip.set_pressure_offset(0.5).expect("offset");                    // Set pressure offset, (offset_hPa float) → ()
                                                                         // writes RPDS_L/RPDS_H (1 LSB = 1/16 hPa)
    chip.set_autozero().expect("autozero");                            // Set AUTOZERO, () → ()
                                                                         // stores the next pressure sample in REF_P
    chip.clear_autozero().expect("clear_autozero");                    // Clear AUTOZERO, () → ()
    chip.set_autorifp().expect("autorifp");                            // Set AUTORIFP, () → ()
                                                                         // stores the next pressure sample in RPDS (persists across resets)
    chip.clear_autorifp().expect("clear_autorifp");                    // Clear AUTORIFP, () → ()
    chip.configure_interrupt(true, false, false, false, 0, false, false).expect("interrupt");  // Configure INT_DRDY routing, (drdy, f_fth, f_ovr, f_fss5, int_s, active_low, open_drain) → ()
                                                                         // selects which events drive the INT_DRDY pin
    chip.configure_pressure_interrupt(true, true, 5.0, true).expect("press_interrupt");  // Configure pressure threshold interrupt, (high_en, low_en, threshold_hPa, latch) → ()
                                                                         // sets THS_P and DIFF_EN/LIR/PHE/PLE bits
    chip.enable_fifo(FIFO_MODE_STREAM, 16).expect("enable_fifo");     // Enable FIFO, (mode 0–7 not 5, watermark 0–31) → ()
                                                                         // sets FIFO_CTRL and FIFO_EN in CTRL_REG2
    let fst = chip.fifo_status().expect("fifo_status");                // Read FIFO_STATUS, () → u8
                                                                         // bit 7 = FTH_FIFO, bit 6 = OVR, bits [5:0] = FSS count
    chip.disable_fifo().expect("disable_fifo");                        // Disable FIFO, () → ()
                                                                         // clears FIFO_EN and resets FIFO_CTRL
    chip.reset_lpf().expect("reset_lpf");                              // Reset LPF, () → ()
                                                                         // reads LPFP_RES to flush transitory state
    chip.reset().expect("soft reset");                                 // Software reset, () → ()
                                                                         // SWRESET in CTRL_REG2, waits for self-clear, restores defaults
    chip.reboot().expect("reboot");                                    // Reboot from Flash, () → ()
                                                                         // sets BOOT in CTRL_REG2, waits for self-clear

    println!("status=0x{:02X}, int_source=0x{:02X}", st, intsrc);
    println!("one_shot: {:.1} Pa / {:.2} C, then: {:.1} Pa / {:.2} C", p, t, p2, t2);
    println!("fifo_status=0x{:02X}", fst);
}