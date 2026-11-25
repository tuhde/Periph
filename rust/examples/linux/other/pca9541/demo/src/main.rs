// Demo: master failover for one of two redundant masters. Prints the power-up
// registers (which reveal the /01 or /03 version), unmasks all interrupts, takes
// the downstream bus with a bus initialization, checks the INT wire with the
// functional test, hands the bus over, takes it back, toggles the connection and
// prints the ISTAT flags every 500 ms for 10 s.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::other::{PCA9541Full, PCA9541_I2C_ADDRESS};
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut selector = PCA9541Full::new(dev, PCA9541_I2C_ADDRESS); // Create PCA9541 Full driver, (i2c, addr=0x70)
    selector.init().expect("init");                                // Run initialization sequence, () → ()

    // --- Power-up state ---
    // CONTROL 0x04 for master 0 means /01 (bus on, master 0); 0x00 means /03 (bus off).
    println!("IE, CONTROL, ISTAT: {:?}", selector.read_all().expect("read_all")); // Read IE, CONTROL, ISTAT, () → (u8, u8, u8)
    println!("status: {:?}", selector.status().expect("status")); // Decode CONTROL, () → PCA9541Status

    // --- Interrupts ---
    selector.set_interrupt_mask(false, false, false, false).expect("unmask"); // Unmask all interrupts, (...) → ()

    // --- Take the bus with a bus initialization ---
    // The chip clocks nine pulses, a NACK and a STOP on the downstream bus first, which
    // completes a transfer the previous master left half-done.
    println!("recover_bus: {}", selector.recover_bus(&mut Delay, 20).expect("recover_bus")); // Recover and take the bus, (delay, timeout_ms) → bool
    sleep(Duration::from_millis(10)); // tBUF: bus-free time before talking to a downstream device
    // ... talk to a downstream device through a second I²C handle here ...

    // --- INT wire check ---
    selector.test_interrupt(true, false).expect("test"); // Drive this master's INT low, (own, other) → ()
    sleep(Duration::from_millis(100));
    selector.test_interrupt(false, false).expect("clear"); // Clear the test, (own, other) → ()

    // --- Hand over and take back ---
    selector.release().expect("release"); // Give the bus to the other master, () → ()
    println!("has control after release: {}", selector.has_control().expect("has_control")); // Report control, () → bool
    println!("acquire: {}", selector.acquire(&mut Delay, false, 20).expect("acquire")); // Take the downstream bus, (delay, bus_init, timeout_ms) → bool
    selector.disconnect().expect("disconnect"); // Isolate the downstream bus, () → ()
    selector.connect().expect("connect"); // Reconnect, () → ()

    // --- Watch the flags for 10 s ---
    for _ in 0..20 {
        println!("ISTAT {:#x}", selector.poll_interrupt().expect("poll_interrupt")); // Read and clear ISTAT, () → u8
        sleep(Duration::from_millis(500));
    }
}
