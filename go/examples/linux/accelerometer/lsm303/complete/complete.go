//go:build linux && !tinygo

// LSM303 complete example — Linux host.
//
// Calls every method of LSM303Full once. Tier-2 lines below each call
// explain what it does.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/accelerometer"
	"github.com/tuhde/Periph/go/periph/connection"
)

func envOr(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	must(err)

	accelConn, err := connection.NewI2CConnection(bus, 0x19, nil, nil) // Create accelerometer connection, (bus, addr=0x19) → (*I2CConnection, error)
	must(err)
	defer accelConn.Close()
	magConn, err := connection.NewI2CConnection(bus, 0x1E, nil, nil) // Create magnetometer connection, (bus, addr=0x1E) → (*I2CConnection, error)
	must(err)
	defer magConn.Close()

	sensor, err := accelerometer.NewLSM303Full(accelConn, magConn) // Create LSM303 Full driver, (accel_conn, mag_conn) → (*LSM303Full, error)
	must(err)
	// runs the initialization sequence and verifies the magnetometer ID

	must(sensor.SetRange(4)) // Set acceleration range, (range_g=4) → error
	// ±4 g full scale; sensitivity becomes 2 mg/LSB in high-resolution mode

	must(sensor.SetDataRate(100)) // Set data rate, (rate_hz=100) → error
	// 100 Hz accelerometer output data rate

	must(sensor.SetMode(2)) // Set accelerometer mode, (mode=2) → error
	// 0 low-power, 1 normal, 2 high-resolution (LPen=0, HR=1)

	must(sensor.SetAxes(true, true, true)) // Enable axes, (x=true, y=true, z=true) → error
	// X, Y, Z acceleration axes all enabled

	ax, ay, az, err := sensor.ReadAcceleration() // Read acceleration, () → (float32, float32, float32, error) g
	must(err)
	// one 6-byte burst so the three axes come from the same sample

	rx, ry, rz, err := sensor.ReadAccelerationRaw() // Read raw counts, () → (int16, int16, int16, error)
	must(err)
	// signed counts at the current resolution (16-bit sample >> 4)

	ready, err := sensor.NewDataAvailable() // Check new sample, () → (bool, error)
	must(err)
	// STATUS_REG_A.ZYXDA

	status, err := sensor.ReadStatus() // Read accelerometer status, () → (uint8, error)
	must(err)
	// raw STATUS_REG_A byte

	must(sensor.SetHighPass(0, 0, false, false, false, false)) // Configure high-pass, (mode=0, cutoff=0, filter_output=false, on_ia1=false, on_ia2=false, on_click=false) → error
	// normal mode, filter bypassed on all functions

	ref, err := sensor.ResetHighPass() // Reset high-pass filter, () → (uint8, error)
	must(err)
	// reading REFERENCE_A resets the filter in HPM = 0

	must(sensor.SetReference(0)) // Set reference value, (value=0) → error
	// REFERENCE_A used when HPM = 1

	must(sensor.SetFifo(2, 16, false)) // Configure FIFO, (mode=2 stream, watermark=16, trigger_int2=false) → error
	// stream mode: the oldest samples are overwritten once full

	level, err := sensor.FifoLevel() // Read FIFO level, () → (uint8, error)
	must(err)
	// number of unread sample sets (FSS)

	samples, err := sensor.ReadFifo(8) // Drain FIFO, (count=8) → ([][3]float32, error) g
	must(err)
	// reads up to 8 sample sets, one 6-byte burst each

	wtm, ovr, empty, fl, err := sensor.ReadFifoStatus() // Decode FIFO status, () → (bool, bool, bool, uint8, error)
	must(err)
	// watermark, overrun, empty, and level from FIFO_SRC_REG_A

	must(sensor.SetFifo(0, 0, false)) // Return FIFO to bypass, (mode=0, watermark=0, trigger_int2=false) → error
	// bypass clears the buffer

	must(sensor.SetMotionInterrupt(1, 0x2A, 0.5, 100, false)) // Configure generator 1, (generator=1, events=0x2A, threshold_g=0.5, duration_ms=100, and_mode=false) → error
	// OR of ZHIE, YHIE and XHIE events

	must(sensor.SetFreeFall(1, 0.35, 30)) // Configure free-fall, (generator=1, threshold_g=0.35, duration_ms=30) → error
	// all three axes low, AND mode (INT1_CFG_A = 0x95)

	must(sensor.SetOrientation(1, false, false)) // Configure 6D orientation, (generator=1, position=false, four_d=false) → error
	// 6D movement recognition on generator 1

	must(sensor.SetLatch(1, true)) // Latch generator 1, (generator=1, enabled=true) → error
	// LIR_INT1: the interrupt stays until INT1_SOURCE_A is read

	ia1, err := sensor.ReadInterruptSource(1) // Read generator 1 source, (generator=1) → (uint8, error)
	must(err)
	// reading clears the latched flags

	must(sensor.SetClick(0x04, 0x01, 0.8, 20, 40, 200)) // Configure click, (single=0x04, double=0x01, threshold_g=0.8, time_limit_ms=20, latency_ms=40, window_ms=200) → error
	// single click on Z, double click on X

	click, err := sensor.ReadClickSource() // Read click source, () → (uint8, error)
	must(err)
	// reading clears the click interrupt

	must(sensor.SetInterruptPolarity(false)) // Set INT polarity, (active_low=false) → error
	// INT1/INT2 active high (H_LACTIVE = 0)

	must(sensor.SetMagRange(130)) // Set magnetometer range, (range_ut=130) → error µT
	// ±1.3 gauss, the default

	must(sensor.SetMagDataRate(15)) // Set magnetometer rate, (rate_hz=15) → error Hz
	// output rate 15 Hz, TEMP_EN preserved

	must(sensor.SetMagMode(0)) // Set magnetometer mode, (mode=0) → error
	// continuous conversion

	mx, my, mz, err := sensor.ReadMagnetic() // Read magnetic field, () → (float32, float32, float32, error) µT
	must(err)
	// reordered from the X, Z, Y wire order

	rmx, rmy, rmz, err := sensor.ReadMagneticRaw() // Read raw magnetic counts, () → (int16, int16, int16, error)
	must(err)
	// signed counts in X, Y, Z order

	mready, err := sensor.MagDataReady() // Check magnetometer sample, () → (bool, error)
	must(err)
	// SR_REG_M.DRDY

	mdrdy, mlock, err := sensor.ReadMagStatus() // Read magnetometer status, () → (bool, bool, error)
	must(err)
	// DRDY and LOCK from SR_REG_M

	ident, err := sensor.ReadMagIdentity() // Read magnetometer identity, () → ([]byte, error)
	must(err)
	// IRA/IRB/IRC, expected 0x48 0x34 0x33

	must(sensor.EnableTemperature(true)) // Enable temperature sensor, (enabled=true) → error
	// sets TEMP_EN in CRA_REG_M

	temp, err := sensor.ReadTemperatureDelta() // Read temperature delta, () → (float32, error) °C
	must(err)
	// relative only, 8 LSB/°C; no absolute reference in the datasheet

	heading, err := sensor.Heading(true) // Read compass heading, (tilt_compensate=true) → (float32, error) degrees
	must(err)
	// tilt-compensated using the accelerometer

	must(sensor.EnableInterrupt(accelerometer.LSM303SourceDataReady, 1)) // Route source, (source=DataReady, pin=1) → error
	// new XYZ sample on INT1

	must(sensor.EnableInterrupt(accelerometer.LSM303SourceClick, 1)) // Route click, (source=Click, pin=1) → error
	// click on INT1 (shared INT1 with DataReady)

	must(sensor.DisableInterrupt(accelerometer.LSM303SourceDataReady, 1)) // Unroute source, (source=DataReady, pin=1) → error
	// removes DataReady from INT1

	flags, err := sensor.PollInterrupt() // Poll interrupts, () → (uint16, error)
	must(err)
	// combined status of every enabled source; clears latched flags

	must(sensor.OnInterrupt(1, func(status uint16) { // Subscribe to INT1, (pin=1, callback) → error
		fmt.Printf("INT1 status=0x%04X\n", status)
	}))
	// without a wired INT pin, a 5 ms polling goroutine is used

	must(sensor.OffInterrupt(1)) // Unsubscribe from INT1, (pin=1) → error
	// stops the callback and polling

	must(sensor.PowerDown()) // Power down accelerometer, () → error
	// ODR = 0; register contents are kept

	must(sensor.Reboot()) // Reboot accelerometer memory, () → error
	// BOOT bit set and waits 5 ms; re-apply configuration afterwards

	fmt.Printf("accel=(%.3f, %.3f, %.3f) g raw=(%d, %d, %d) ready=%v status=0x%02X\n", ax, ay, az, rx, ry, rz, ready, status)
	fmt.Printf("ref=0x%02X level=%d samples=%d wtm=%v ovr=%v empty=%v fl=%d\n", ref, level, len(samples), wtm, ovr, empty, fl)
	fmt.Printf("ia1=0x%02X click=0x%02X mag=(%.1f, %.1f, %.1f) uT raw=(%d, %d, %d)\n", ia1, click, mx, my, mz, rmx, rmy, rmz)
	fmt.Printf("mag_ready=%v drdy=%v lock=%v ident=% X temp=%.1f C heading=%.1f deg mready=%v\n",
		mready, mdrdy, mlock, ident, temp, heading, mready)
	fmt.Printf("flags=0x%04X\n", flags)
}
