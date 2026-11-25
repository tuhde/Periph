//go:build linux && !tinygo

// MMA8451Q complete example — exercise every method in the public API.
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

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_BUS:", err)
		os.Exit(2)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x1C"), 0, 8)
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_ADDR:", err)
		os.Exit(2)
	}
	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil)
	if err != nil {
		fmt.Fprintln(os.Stderr, "connection:", err)
		os.Exit(2)
	}
	defer conn.Close()
	chip, err := accelerometer.NewMMA8451QFull(conn) // Create MMA8451Q Full driver, (connection) → (*MMA8451QFull, error)
	if err != nil {
		fmt.Fprintln(os.Stderr, "init:", err)
		os.Exit(2)
	}

	_ = chip.SetRange(4)                                                                            // Set measurement range, (rangeG=4) → error
	_ = chip.SetDataRate(100)                                                                       // Set output data rate, (rateHz=100) → error
	_ = chip.SetOversampling(0, nil)                                                                // Set oversampling mode, (mode=0, sleepMode=nil) → error
	_ = chip.SetLowNoise(false)                                                                     // Set low-noise mode, (enabled=false) → error
	_ = chip.SetResolution(14)                                                                      // Set output resolution, (bits=14) → error
	_ = chip.SetHighPassOutput(false, 0)                                                            // Set high-pass output, (enabled=false, cutoff=0) → error
	_ = chip.SetOffset(0.01, 0.0, -0.01)                                                            // Set zero-g offsets, (xG, yG, zG) → error
	_ = chip.SetFIFO(1, 16)                                                                         // Set FIFO mode, (mode=1 circular, watermark=16) → error
	_ = chip.SetFIFOTrigger(false, false, true, false)                                              // Set FIFO trigger sources, (transient, orientation, pulse, motion) → error
	_ = chip.SetFreefallMotion(false, 0x07, 0.2, 60, true, false)                                   // Set freefall/motion detector, (motion=false, axes=0x07, thresholdG=0.2, debounceMs=60, latch=true, debounceClear=false) → error
	_ = chip.SetTransient(0x07, 0.2, 60, false, true)                                               // Set transient detector, (axes=0x07, thresholdG=0.2, debounceMs=60, bypassHPF=false, latch=true) → error
	_ = chip.SetPulse(0x04, 0x04, [3]float32{0.5, 0.5, 0.5}, 30, 100, 300, true, false, true, true) // Set tap detector, (single=0x04, double=0x04, thresholdG=(0.5,0.5,0.5), timeLimitMs=30, latencyMs=100, windowMs=300, latch=true, doubleAbort=false, lpf=true, hpf=true) → error
	_ = chip.SetOrientation(true, 100, 45, 4, 4, 1)                                                 // Set orientation detector, (enabled=true, debounceMs=100, thresholdDeg=45, hysteresis=4, zLock=4, backFront=1) → error
	_ = chip.SetAutoSleep(false, 50, 10, 0)                                                         // Set auto-sleep, (enabled=false, sleepRateHz=50, timeoutS=10, wakeSources=0) → error
	_ = chip.SetFIFOGate(false)                                                                     // Set FIFO gate, (enabled=false) → error
	_ = chip.SetInterruptPins(false, false)                                                         // Set interrupt pins, (activeHigh=false, openDrain=false) → error
	_ = chip.EnableInterrupt(accelerometer.MMA8451QSourceDataReady, 1)                              // Enable interrupt source, (source=DATA_READY, pin=1) → error
	_ = chip.DisableInterrupt(accelerometer.MMA8451QSourceDataReady)                                // Disable interrupt source, (source=DATA_READY) → error

	rx, ry, rz, _ := chip.ReadRaw()                           // Read raw counts, () → (int32, int32, int32, error)
	ready, _ := chip.NewDataAvailable()                       // Check new data available, () → (bool, error)
	status, _ := chip.ReadStatus()                            // Read STATUS register, () → (uint8, error)
	mode, _ := chip.ReadSystemMode()                          // Read system mode, () → (uint8, error)
	ox, oy, oz, _ := chip.GetOffset()                         // Read zero-g offsets, () → (float32 g, float32 g, float32 g, error)
	level, _ := chip.FIFOLevel()                              // Read FIFO level, () → (uint8, error)
	ovf, wmrk, count, _ := chip.ReadFIFOStatus()              // Read FIFO status, () → (bool, bool, uint8, error)
	motion, _ := chip.ReadMotionSource()                      // Read freefall/motion source, () → (uint8, error)
	transient, _ := chip.ReadTransientSource()                // Read transient source, () → (uint8, error)
	pulse, _ := chip.ReadPulseSource()                        // Read tap source, () → (uint8, error)
	changed, lockout, lapo, back, _ := chip.ReadOrientation() // Read orientation status, () → (bool, bool, uint8, bool, error)
	intStatus, _ := chip.PollInterrupt()                      // Poll interrupt status, () → (uint8, error)
	fifo, _ := chip.ReadFIFO(-1)                              // Drain the FIFO, (count=-1) → ([][]float32, error)
	x, y, z, _ := chip.Read()                                 // Read acceleration, () → (float32 g, float32 g, float32 g, error)
	ok, dx, dy, dz, _ := chip.SelfTest()                      // Run the self-test, () → (bool, int32, int32, int32, error)

	_ = chip.Standby()  // Switch to standby, () → error
	_ = chip.Activate() // Switch to active, () → error
	_ = chip.Reset()    // Software reset, () → error

	fmt.Printf("raw=(%d,%d,%d) ready=%v status=0x%02X mode=%d\n", rx, ry, rz, ready, status, mode)
	fmt.Printf("offset=(%.3f, %.3f, %.3f) fifo_level=%d fifo_status=(%v,%v,%d) samples=%d\n",
		ox, oy, oz, level, ovf, wmrk, count, len(fifo))
	fmt.Printf("motion=0x%02X transient=0x%02X pulse=0x%02X int=0x%02X\n",
		motion, transient, pulse, intStatus)
	fmt.Printf("orientation=(%v,%v,%d,%v) xyz=(%.3f, %.3f, %.3f) selftest=(%v,%d,%d,%d)\n",
		changed, lockout, lapo, back, x, y, z, ok, dx, dy, dz)
}
