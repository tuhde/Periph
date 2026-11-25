//go:build linux && !tinygo

// FXOS8700 complete example — Linux host.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

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
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x1E"), 0, 8)
	must(err)

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x1E) → (*I2CConnection, error)
	must(err)
	defer conn.Close()

	chip, err := accelerometer.NewFXOS8700Full(conn) // Create FXOS8700 Full driver, (connection) → (*FXOS8700Full, error)
	// verifies WHO_AM_I, resets and starts hybrid mode
	must(err)

	ax, ay, az, err := chip.ReadAccel() // Read acceleration, () → (float32 g, float32 g, float32 g, error)
	// burst-reads 0x01-0x06, 14-bit left-justified
	must(err)
	mx, my, mz, err := chip.ReadMag() // Read magnetic field, () → (float32 µT, float32 µT, float32 µT, error)
	// burst-reads 0x33-0x38, 0.1 µT/LSB
	must(err)
	fmt.Printf("accel %.4f %.4f %.4f g, mag %.2f %.2f %.2f uT\n", ax, ay, az, mx, my, mz)
	accel, mag, err := chip.ReadHybrid() // Read both sensors, () → ([3]float32 g, [3]float32 µT, error)
	// one 12-byte burst from 0x01 (hyb_autoinc_mode)
	must(err)
	fmt.Println("hybrid", accel, mag)
	rx, ry, rz, err := chip.ReadRawAccel() // Read raw accelerometer, () → (int16, int16, int16, error) counts
	// signed 14-bit counts
	must(err)
	qx, qy, qz, err := chip.ReadRawMag() // Read raw magnetometer, () → (int16, int16, int16, error) counts
	// signed 16-bit counts, 0.1 µT/LSB
	must(err)
	fmt.Println("raw", rx, ry, rz, qx, qy, qz)
	temperature, err := chip.ReadTemperature() // Read die temperature, () → (float32 °C, error)
	// int8(TEMP) × 0.96, relative only
	must(err)
	fmt.Println("temperature", temperature)
	newAccel, err := chip.NewAccelData() // Check accelerometer data ready, () → (bool, error)
	// STATUS.zyxdr
	must(err)
	newMag, err := chip.NewMagData() // Check magnetometer data ready, () → (bool, error)
	// M_DR_STATUS.zyxdr
	must(err)
	fmt.Println("new data", newAccel, newMag)
	status, err := chip.ReadStatus() // Read STATUS, () → (uint8, error)
	// raw STATUS byte
	must(err)
	magStatus, err := chip.ReadMagStatus() // Read M_DR_STATUS, () → (uint8, error)
	// raw M_DR_STATUS byte
	must(err)
	mode, err := chip.ReadSystemMode() // Read SYSMOD, () → (uint8, error) 0 standby | 1 wake | 2 sleep
	// SYSMOD bits 1:0
	must(err)
	fmt.Println("status", status, magStatus, mode)

	must(chip.SetMode(accelerometer.FXOS8700ModeHybrid)) // Select operating mode, (mode=0|1|2) → error
	// 0 accel only, 1 mag only, 2 hybrid
	must(chip.SetRange(4)) // Set accelerometer range, (rangeG=2|4|8) → error
	// rewrites XYZ_DATA_CFG.fs and the cached sensitivity
	must(chip.SetDataRate(200)) // Set system data rate, (rateHz=800..1.5625) → error
	// each sensor runs at half this rate in hybrid mode
	must(chip.SetAccelOversampling(0, 0)) // Set accelerometer oversampling, (mode=0..3, sleepMode=-1) → error
	// MODS / SMODS in CTRL_REG2
	must(chip.SetMagOversampling(7, 7)) // Set magnetometer oversampling, (ratio=0..7, sleepRatio=-1) → error
	// m_os / m_aslp_os
	must(chip.SetLowNoise(false)) // Set low-noise mode, (enabled) → error
	// lnoise limits the range to ±4 g
	must(chip.SetResolution(14)) // Set accelerometer resolution, (bits=14|8) → error
	// 8 selects fast read (MSB only)
	must(chip.SetHighPassOutput(false, 0)) // Set high-pass output, (enabled, cutoff=0..3) → error
	// hpf_out and sel
	must(chip.Standby()) // Enter STANDBY, () → error
	// polls SYSMOD until 0 (up to 300 ms)
	must(chip.Activate()) // Enter ACTIVE, () → error
	// waits the 2/ODR + 2 ms turn-on time

	must(chip.SetAccelOffset(0, 0, 0)) // Set accelerometer offsets, (xG, yG, zG) → error
	// OFF_X/Y/Z, 2 mg/LSB
	ox, oy, oz, err := chip.GetAccelOffset() // Get accelerometer offsets, () → (float32 g, float32 g, float32 g, error)
	// signed 8-bit registers
	must(err)
	must(chip.SetMagOffset(0, 0, 0)) // Set hard-iron offsets, (xUt, yUt, zUt) → error
	// 15-bit values subtracted from the output
	hx, hy, hz, err := chip.GetMagOffset() // Get hard-iron offsets, () → (float32 µT, float32 µT, float32 µT, error)
	// M_OFF_X/Y/Z
	must(err)
	fmt.Println("offsets", ox, oy, oz, hx, hy, hz)
	must(chip.SetMagRaw(false)) // Select raw magnetometer output, (raw) → error
	// false applies the hard-iron offsets
	must(chip.SetMagAutoCalibration(true)) // Hardware hard-iron calibration, (enabled) → error
	// MAX/MIN midpoint → M_OFF_* every ODR cycle
	time.Sleep(time.Second)
	minUt, maxUt, err := chip.ReadMagMinMax() // Read tracked min / max, () → ([3]float32 µT, [3]float32 µT, error)
	// MIN_* / MAX_* registers
	must(err)
	fmt.Println("min/max", minUt, maxUt)
	must(chip.SetMagAutoCalibration(false))
	must(chip.ResetMagMinMax()) // Reset min / max tracker, () → error
	// m_maxmin_rst
	must(chip.SetMagReset(0)) // Set automatic magnetic reset, (mode=0..3) → error
	// 0 every ODR cycle, 3 disabled
	must(chip.TriggerMagReset()) // Trigger one-shot degauss, () → error
	// m_rst
	sx, sy, sz, err := chip.MeasureMagOnce() // Measure once in STANDBY, () → (float32 µT, float32 µT, float32 µT, error)
	// m_ost, restores ACTIVE
	must(err)
	fmt.Println("one-shot", sx, sy, sz)
	heading, err := chip.Heading(2.5) // Compute tilt-compensated heading, (declinationDeg) → (float32 degrees, error)
	// software compensation from one accel + one mag sample
	must(err)
	fmt.Println("heading", heading)

	must(chip.SetFifo(accelerometer.FXOS8700FifoCircular, 16)) // Configure FIFO, (mode=0..3, watermark) → error
	// circular mode with a 16-sample watermark
	must(chip.SetFifoTrigger(false, false, false, true)) // Select FIFO trigger sources, (transient, orientation, pulse, motion) → error
	// TRIG_CFG
	must(chip.SetFifoGate(false)) // Set FIFO gate, (enabled) → error
	// fifo_gate
	time.Sleep(200 * time.Millisecond)
	level, err := chip.FifoLevel() // Read FIFO level, () → (uint8, error)
	// f_cnt, 0-32
	must(err)
	overflow, watermark, count, err := chip.ReadFifoStatus() // Read FIFO status, () → (bool, bool, uint8, error)
	// decodes F_STATUS
	must(err)
	samples, err := chip.ReadFifo(4) // Drain FIFO, (count) → ([][3]float32 g, error)
	// one 6-byte transaction per sample
	must(err)
	fmt.Println("fifo", level, overflow, watermark, count, samples)
	must(chip.SetFifo(accelerometer.FXOS8700FifoDisabled, 0))

	must(chip.SetFreefallMotion(true, 7, 0.3, 60, true, false)) // Configure freefall / motion, (motion, axes, thresholdG, debounceMs, latch, debounceClear) → error
	// A_FFMT_CFG / THS / COUNT
	motion, err := chip.ReadMotionSource() // Read A_FFMT_SRC, () → (uint8, error)
	// clears the flags when latched
	must(err)
	must(chip.SetTransient(7, 0.4, 20, false, false)) // Configure transient detector, (axes, thresholdG, debounceMs, bypassHpf, latch) → error
	// TRANSIENT_CFG / THS / COUNT
	transient, err := chip.ReadTransientSource() // Read TRANSIENT_SRC, () → (uint8, error)
	// clears the interrupt
	must(err)
	pulseCfg := accelerometer.DefaultFXOS8700PulseConfig()
	pulseCfg.Single = 0x04
	must(chip.SetPulse(pulseCfg)) // Configure tap detection, (FXOS8700PulseConfig) → error
	// single tap on Z with default thresholds
	pulse, err := chip.ReadPulseSource() // Read PULSE_SRC, () → (uint8, error)
	// clears the interrupt
	must(err)
	fmt.Println("sources", motion, transient, pulse)
	must(chip.SetOrientation(true, 100, 45, 4, 4, 2)) // Configure orientation detection, (enabled, debounceMs, thresholdDeg, hysteresis, zLock, backFront) → error
	// PL_CFG / COUNT / THS / BF_ZCOMP
	changed, lockout, pl, back, err := chip.ReadOrientation() // Read orientation, () → (changed, lockout bool, portraitLandscape uint8, back bool, error)
	// clears newlp
	must(err)
	fmt.Println("orientation", changed, lockout, pl, back)
	must(chip.SetAutoSleep(true, 12.5, 10, accelerometer.FXOS8700SourceFfMt)) // Configure Auto-SLEEP, (enabled, sleepRateHz, timeoutS, wakeSources) → error
	// sleeps after 10 s of inactivity, motion wakes

	must(chip.SetInterruptPins(false, false)) // Configure INT pins, (activeHigh, openDrain) → error
	// ipol / pp_od
	must(chip.EnableInterrupt(accelerometer.FXOS8700SourceFfMt|accelerometer.FXOS8700SourceOrientation, 1)) // Enable and route interrupt, (source mask, pin=1|2) → error
	// CTRL_REG4 enable, CTRL_REG5 routing
	pending, err := chip.PollInterrupt() // Poll and clear interrupt sources, () → (uint8, error)
	// reads INT_SOURCE and the matching source registers
	must(err)
	fmt.Println("interrupts", pending)
	must(chip.OnInterrupt(func(status uint8) { fmt.Println("INT", status) })) // Subscribe to INT, (callback func(uint8)) → error
	// uses the INT pin, or a 5 ms polling goroutine
	time.Sleep(time.Second)
	must(chip.OffInterrupt()) // Unsubscribe from INT, () → error
	// stops the delivery
	must(chip.DisableInterrupt(accelerometer.FXOS8700SourceFfMt | accelerometer.FXOS8700SourceOrientation)) // Disable interrupt sources, (source mask) → error
	// clears int_en_*

	reg, err := chip.ReadRegister(0x5F, 1) // Read raw registers, (reg, length) → ([]byte, error)
	// A_VECM_CFG, not wrapped by the API
	must(err)
	must(chip.WriteRegister(0x5F, reg)) // Write raw registers, (reg, data) → error
	// no STANDBY handling
	ok, dx, dy, dz, err := chip.SelfTest() // Run accelerometer self-test, () → (bool, int, int, int, error) counts
	// electrostatic test, checks X ≥ 192, Y ≥ 270, Z ≥ 1275
	must(err)
	fmt.Println("self-test", ok, dx, dy, dz)
	must(chip.Reset()) // Software reset, () → error
	// the NACK on the write is tolerated; call Init() afterwards
	must(chip.Init()) // Re-initialise, () → error
	// verifies WHO_AM_I and restarts hybrid mode
}
