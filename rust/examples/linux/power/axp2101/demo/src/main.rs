use linux_embedded_hal::I2cdev;
use periph::chips::power::Axp2101Full;
use periph::chips::power::axp2101::{
    SOURCE_CHARGE_DONE, SOURCE_CHARGE_START, SOURCE_PWRON_SHORT, SOURCE_SOC_WARN_L2, SOURCE_VBUS_INSERT,
    SOURCE_VBUS_REMOVE,
};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x34);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut chip = Axp2101Full::new(dev, addr).expect("init AXP2101");         // Create AXP2101 Full driver, (i2c, addr=0x34) → Axp2101Full
    std::thread::sleep(std::time::Duration::from_millis(20));

    let events: [(u32, &str); 6] = [
        (SOURCE_VBUS_INSERT, "VBUS inserted"),
        (SOURCE_VBUS_REMOVE, "VBUS removed"),
        (SOURCE_CHARGE_START, "charging started"),
        (SOURCE_CHARGE_DONE, "charging done"),
        (SOURCE_PWRON_SHORT, "power key short press"),
        (SOURCE_SOC_WARN_L2, "battery warning level 2"),
    ];

    // --- Report why the board started and what every rail is doing ---
    // eFuse defaults differ per product, so the rails are read, never assumed.
    let src = chip.power_on_source().expect("power_on_source");                // Read power-on flags, () → u8
    println!("power-on source: 0x{:02X}", src);
    for name in ["dcdc1", "dcdc2", "dcdc3", "dcdc4", "aldo1", "aldo2", "aldo3", "aldo4",
                 "bldo1", "bldo2", "cpusldo", "dldo1", "dldo2"] {
        let (enabled, volts) = chip.get_rail(name).expect("get_rail");         // Read rail state, (name) → (bool, f32 V)
        println!("{:8} {:3} {:.2} V", name, if enabled { "on" } else { "off" }, volts);
    }

    // --- Program a conservative 4.2 V / 500 mA charge profile ---
    // 4.2 V suits a standard Li-ion cell; 500 mA keeps heating low; the 1.5 A input
    // limit lets a USB charger feed both the system and the charger.
    chip.set_charge_voltage(4.2).expect("set_charge_voltage");                 // Set charge target, (volts 4.0/4.1/4.2/4.35/4.4) → ()
    chip.set_charge_current(0.5).expect("set_charge_current");                 // Set charge current, (amps 0–1.5) → ()
    chip.set_input_current_limit(1.5).expect("set_input_current_limit");       // Set input current limit, (amps 0.1/0.5/0.9/1.0/1.5/2.0) → ()
    chip.set_termination(0.05, true).expect("set_termination");                // Set termination current, (amps 0–0.2, enabled) → ()

    // --- Arm low-battery warnings and the power events of interest ---
    // The warning levels are 15 % and 5 %; the six sources below raise the IRQ pin.
    chip.configure_battery_warning(15, 5).expect("configure_battery_warning"); // Set warning levels, (level2 %, level1 %) → ()
    for (mask, _) in events.iter() {
        chip.enable_interrupt(*mask).expect("enable_interrupt");               // Enable interrupt source, (source) → ()
    }
    chip.poll_interrupt().expect("poll_interrupt");                            // Clear stale status, () → u32

    // --- Monitor for 120 s ---
    // One status line per second; poll_interrupt() returns and clears every pending event.
    for _ in 0..120 {
        let d = chip.read_all().expect("read_all");                            // Read all measurements, () → Axp2101Readings
        let st = chip.status().expect("status");                               // Read status flags, () → Axp2101Status
        println!("{:.3} V {:3} %  vbus {} {:.2} V  sys {:.2} V  die {:.1} C  state {}  {}",
                 d.battery_voltage, d.battery_percent, d.vbus_present, d.vbus_voltage,
                 d.system_voltage, d.die_temperature, d.charge_state, st.battery_direction);
        let got = chip.poll_interrupt().expect("poll_interrupt");              // Read and clear events, () → u32
        for (mask, text) in events.iter() {
            if got & *mask != 0 {
                println!("event: {}", text);
            }
        }
        std::thread::sleep(std::time::Duration::from_secs(1));
    }
}
