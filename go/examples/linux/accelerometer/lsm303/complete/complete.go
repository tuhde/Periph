//go:build linux && !tinygo

// LSM303 complete example — Linux host.
//
// Exercises every method in the LSM303Full API: accelerometer range /
// data-rate / mode / axis configuration, the high-pass filter, the FIFO,
// the inertial interrupt generators and click detection, interrupt
// routing, the magnetometer gain / rate / mode controls, the relative
// temperature, the compass heading, and reboot.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/accelerometer"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		panic(err)
	}

	accelConn, err := connection.NewI2CConnection(bus, 0x19, nil, nil) // Create accelerometer I²C connection, (bus=1, addr=0x19) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer accelConn.Close()
	magConn, err := connection.NewI2CConnection(bus, 0x1E, nil, nil) // Create magnetometer I²C connection, (bus=1, addr=0x1E) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer magConn.Close()

	chip, err := accelerometer.NewLSM303Full(accelConn, magConn) // Create LSM303 driver, (accelConn, magConn) → (*LSM303Full, error)
	if err != nil {
		panic(err)
	}

	// --- Accelerometer configuration ---
	if err := chip.SetRange(4); err != nil { // Set accelerometer full-scale, (range_g=2|4|8|16) → error
		panic(err)
	}
	// updates the cached sensitivity (2 mg/LSB at ±4 g high-resolution)

	if err := chip.SetDataRate(50); err != nil { // Set accelerometer output data rate, (rate_hz=1|10|25|50|100|200|400|1344|1620|5376) → error
		panic(err)
	}
	// 1620/5376 Hz require low-power mode, 1344 Hz normal/high-resolution

	if err := chip.SetMode(2); err != nil { // Set operating mode, (mode=0 low-power|1 normal|2 high-resolution) → error
		panic(err)
	}
	// rewrites LPen (CTRL_REG1_A) and HR (CTRL_REG4_A); updates the sensitivity

	if err := chip.SetAxes(true, true, true); err != nil { // Enable/disable axes, (x, y, z bool) → error
		panic(err)
	}
	// Xen/Yen/Zen in CTRL_REG1_A bits 2:0

	if err := chip.SetHighPass(0, 0, true, true, false, false); err != nil { // Configure high-pass filter, (mode=0–3, cutoff=0–3, filter_output, on_ia1, on_ia2, on_click) → error
		panic(err)
	}
	// filtered data goes to the output registers and to generator 1

	ref, err := chip.ResetHighPass() // Reset high-pass filter, () → (uint8, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("high-pass reference: %d\n", ref) // reading REFERENCE_A resets the filter in HPM=00

	if err := chip.SetReference(0x10); err != nil { // Write high-pass reference, (value=0–255) → error
		panic(err)
	}
	// used as the reference signal when HPM = 01

	// --- FIFO ---
	if err := chip.SetFifo(2, 16, false); err != nil { // Configure FIFO, (mode=0 bypass|1 FIFO|2 stream|3 trigger, watermark=0–31, trigger_int2) → error
		panic(err)
	}
	// stream mode, 16-sample watermark, INT1 as the trigger source

	level, err := chip.FifoLevel() // Read unread FIFO sample count, () → (uint8 0–32, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("fifo level: %d\n", level) // FSS<4:0> from FIFO_SRC_REG_A

	samples, err := chip.ReadFifo(0) // Drain FIFO samples, (count ≤ 0 = all unread) → ([][3]float32 g, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("fifo drained: %d samples\n", len(samples)) // one 6-byte burst pops one sample set

	watermark, overrun, empty, level, err := chip.ReadFifoStatus() // Read decoded FIFO status, () → (watermark, overrun, empty bool, level uint8, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("fifo status: wtm=%v ovrn=%v empty=%v level=%d\n", watermark, overrun, empty, level)

	if err := chip.SetFifo(0, 0, false); err != nil { // Return the FIFO to bypass, (mode=0, watermark=0, trigger_int2=false) → error
		panic(err)
	}
	// only bypass empties the buffer

	// --- Inertial interrupt generators and click ---
	if err := chip.SetMotionInterrupt(1, 0x2A, 1.0, 100, false); err != nil { // Configure a generator, (generator=1|2, events=6-bit mask, threshold_g, duration_ms, and_mode) → error
		panic(err)
	}
	// any axis high/low above 1 g for 100 ms, OR-combined

	if err := chip.SetFreeFall(2, 0.35, 30); err != nil { // Configure free-fall, (generator=1|2, threshold_g, duration_ms) → error
		panic(err)
	}
	// all three axes low, AND-combined (INTx_CFG_A = 0x95)

	if err := chip.SetOrientation(1, false, false); err != nil { // Configure 6D/4D orientation, (generator=1|2, position, four_d) → error
		panic(err)
	}
	// 6D movement recognition: one-ODR pulse when the orientation changes

	if err := chip.SetLatch(1, true); err != nil { // Latch a generator's interrupt, (generator=1|2, enabled) → error
		panic(err)
	}
	// INT1 stays asserted until INT1_SOURCE_A is read

	src, err := chip.ReadInterruptSource(1) // Read a generator's source, (generator=1|2) → (uint8, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("generator 1 source: 0x%02X\n", src) // reading clears the latched interrupt

	if err := chip.SetClick(1, 4, 0.5, 10, 20, 50); err != nil { // Configure click detection, (single, double=axis mask X=1|Y=2|Z=4, threshold_g, time_limit_ms, latency_ms, window_ms) → error
		panic(err)
	}
	// single click on X, double click on Z, 0.5 g threshold

	click, err := chip.ReadClickSource() // Read the click source, () → (uint8, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("click source: 0x%02X\n", click) // reading clears the click interrupt

	if err := chip.SetInterruptPolarity(true); err != nil { // Set INT pin polarity, (active_low) → error
		panic(err)
	}
	// H_LACTIVE in CTRL_REG6_A bit 1
	if err := chip.SetInterruptPolarity(false); err != nil { // Restore active-high INT pins, (active_low=false) → error
		panic(err)
	}

	// --- Interrupt routing and polling ---
	if err := chip.EnableInterrupt(accelerometer.SourceClick, 1); err != nil { // Route a source to an INT pin, (source, pin=1|2) → error
		panic(err)
	}
	// sets I1_CLICK in CTRL_REG3_A
	if err := chip.EnableInterrupt(accelerometer.SourceIA1, 1); err != nil { // Route generator 1 to INT1, (source, pin=1|2) → error
		panic(err)
	}
	// sets I1_AOI1 in CTRL_REG3_A
	if err := chip.EnableInterrupt(accelerometer.SourceMagDataReady, 1); err == nil {
		panic("SourceMagDataReady must be rejected — it is on the DRDY pin")
	}
	// magnetometer DRDY is not routable to INT1/INT2

	status, err := chip.PollInterrupt() // Poll all enabled sources, () → (uint16 combined status, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("interrupt status: 0x%04X\n", status) // reading clears the latched source flags

	if err := chip.DisableInterrupt(accelerometer.SourceClick, 1); err != nil { // Unroute a source, (source, pin=1|2) → error
		panic(err)
	}
	// clears I1_CLICK in CTRL_REG3_A

	// --- Reads and raw data ---
	if err := chip.SetDataRate(100); err != nil { // Back to the 100 Hz default rate, (rate_hz) → error
		panic(err)
	}

	x, y, z, err := chip.ReadAcceleration() // Read 3-axis acceleration, () → (float32 g, float32 g, float32 g, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("accel: (%.3f %.3f %.3f) g\n", x, y, z) // converts raw left-justified registers to g

	mx, my, mz, err := chip.ReadMagnetic() // Read 3-axis magnetic flux density, () → (float32 µT, float32 µT, float32 µT, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("mag: (%.1f %.1f %.1f) uT\n", mx, my, mz) // reorders the X, Z, Y wire order to X, Y, Z

	rax, ray, raz, err := chip.ReadAccelerationRaw() // Read raw accelerometer counts, () → (int16, int16, int16, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("accel raw: (%d %d %d)\n", rax, ray, raz) // signed counts at the current resolution

	rmx, rmy, rmz, err := chip.ReadMagneticRaw() // Read raw magnetometer counts, () → (int16, int16, int16, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("mag raw: (%d %d %d)\n", rmx, rmy, rmz) // signed 12-bit counts, X/Y/Z order

	ready, err := chip.NewDataAvailable() // Check for new accelerometer data, () → (bool, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("accel data ready: %v\n", ready) // STATUS_REG_A.ZYXDA

	statusA, err := chip.ReadStatus() // Read the accelerometer status register, () → (uint8, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("accel status: 0x%02X\n", statusA) // overrun + data-ready flags

	magReady, err := chip.MagDataReady() // Check for new magnetometer data, () → (bool, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("mag data ready: %v\n", magReady) // SR_REG_M.DRDY

	drdy, lock, err := chip.ReadMagStatus() // Read the magnetometer status, () → (drdy, lock bool, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("mag status: drdy=%v lock=%v\n", drdy, lock) // LOCK = output block read in progress

	id, err := chip.ReadMagIdentity() // Read the magnetometer identity, () → ([3]byte, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("mag identity: %X\n", id) // IRA/IRB/IRC = "H43" (same as HMC5883L)

	// --- Magnetometer configuration ---
	if err := chip.SetMagRange(400); err != nil { // Set magnetometer range, (range_ut=130|190|250|400|470|560|810) → error
		panic(err)
	}
	// writes GN to CRB_REG_M; updates the cached LSB/gauss gains
	if err := chip.SetMagRange(130); err != nil { // Back to ±130 µT, (range_ut) → error
		panic(err)
	}

	if err := chip.SetMagDataRate(30); err != nil { // Set magnetometer data rate, (rate_hz=0.75|1.5|3|7.5|15|30|75|220) → error
		panic(err)
	}
	// RMW of CRA_REG_M DO bits, keeps TEMP_EN
	if err := chip.SetMagDataRate(15); err != nil { // Back to 15 Hz, (rate_hz) → error
		panic(err)
	}

	if err := chip.SetMagMode(0); err != nil { // Set magnetometer mode, (mode=0 continuous|1 single|2 sleep) → error
		panic(err)
	}
	// single mode performs one conversion then returns to sleep

	// --- Temperature and heading ---
	if err := chip.EnableTemperature(true); err != nil { // Enable the temperature sensor, (enabled) → error
		panic(err)
	}
	// TEMP_EN in CRA_REG_M bit 7; updates at the magnetometer data rate

	delta, err := chip.ReadTemperatureDelta() // Read relative temperature, () → (float32 °C, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("temperature delta: %.3f C\n", delta) // relative only, 8 LSB/°C

	heading, err := chip.Heading(true) // Compute compass heading, (tilt_compensate=true) → (float64 degrees 0–360, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("heading: %.1f deg\n", heading) // magnetic north, atan2(y, x) convention

	flat, err := chip.Heading(false) // Compute a flat heading, (tilt_compensate=false) → (float64 degrees 0–360, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("flat heading: %.1f deg\n", flat) // magnetic X/Y only, drifts with tilt

	// --- Power and reboot ---
	if err := chip.PowerDown(); err != nil { // Enter accelerometer power-down, () → error
		panic(err)
	}
	// ODR = 0000; register contents are retained

	if err := chip.Reboot(); err != nil { // Reboot accelerometer memory, () → error
		panic(err)
	}
	// BOOT = 1; construct a fresh driver afterwards
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
