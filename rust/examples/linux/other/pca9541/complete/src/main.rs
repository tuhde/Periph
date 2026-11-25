// Complete example: exercise every method in the public API.
// Reads the registers, takes the bus (with and without bus initialization),
// disconnects and reconnects the downstream bus, programs the interrupt masks,
// runs the INT functional test and reads the interrupt status.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::other::{PCA9541Full, PCA9541_I2C_ADDRESS, PCA9541_SOURCE_INT_IN};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut selector = PCA9541Full::new(dev, PCA9541_I2C_ADDRESS); // Create PCA9541 Full driver, (i2c, addr=0x70)
    selector.init().expect("init");                                // Run initialization sequence, () → ()
    // reads CONTROL and ISTAT; writes nothing

    println!("CONTROL {:#x}", selector.get_control().expect("get_control")); // Read the CONTROL byte, () → u8
    println!("{:?}", selector.status().expect("status")); // Decode CONTROL, () → PCA9541Status
    println!("{:?}", selector.read_all().expect("read_all")); // Read IE, CONTROL, ISTAT, () → (u8, u8, u8)
    // one auto-increment read; clears the ISTAT flags

    println!("acquire {}", selector.acquire(&mut Delay, false, 20).expect("acquire")); // Take the downstream bus, (delay, bus_init, timeout_ms) → bool
    // reads CONTROL, writes MYBUS/BUSON per the datasheet rule
    println!("recover {}", selector.recover_bus(&mut Delay, 20).expect("recover_bus")); // Recover and take the bus, (delay, timeout_ms) → bool
    // nine clocks, NACK and STOP on the downstream bus, then connect
    println!("control {}", selector.has_control().expect("has_control")); // Report control, () → bool
    println!("connected {}", selector.is_connected().expect("is_connected")); // Report the connection, () → bool
    // BUSON != NBUSON: some master is connected
    selector.disconnect().expect("disconnect"); // Isolate the downstream bus, () → ()
    // BUSON = NBUSON; ownership bits are left
    selector.connect().expect("connect"); // Reconnect without changing ownership, () → ()

    selector.set_interrupt_mask(false, true, false, false).expect("mask"); // Write IE, (bus_lost, bus_not_idle, bus_init, int_in) → ()
    // true = masked, no interrupt
    println!("mask {}", selector.get_interrupt_mask().expect("get mask")); // Read IE, () → u8
    selector.disable_interrupt(PCA9541_SOURCE_INT_IN).expect("disable"); // Mask one source, (source) → ()
    selector.enable_interrupt(PCA9541_SOURCE_INT_IN).expect("enable"); // Unmask one source, (source) → ()

    selector.test_interrupt(true, false).expect("test_interrupt"); // Drive this master's INT low, (own, other) → ()
    // the bus bits are written back unchanged
    selector.test_interrupt(false, false).expect("clear test"); // Clear the test, (own, other) → ()
    println!("ISTAT {:#x}", selector.poll_interrupt().expect("poll_interrupt")); // Read and clear ISTAT, () → u8
    // call it from your INT falling-edge handler

    selector.release().expect("release"); // Give the bus to the other master, () → ()
}
