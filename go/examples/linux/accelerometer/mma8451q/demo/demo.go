//go:build linux && !tinygo

// MMA8451Q demo — orientation, tap and freefall monitor with Auto-SLEEP.
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

	// --- Configure for a noise-sensitive tilt sensor ---
	// ±4 g leaves headroom for shocks, 100 Hz is fast enough for a
	// responsive orientation detector, and the high-resolution oversampling
	// mode trades current for a quieter signal.
	_ = chip.SetRange(4)      // Set measurement range, (rangeG=4) → error
	_ = chip.SetDataRate(100) // Set output data rate, (rateHz=100) → error
	hr := uint8(2)
	_ = chip.SetOversampling(hr, nil) // Set oversampling mode, (mode=2 high resolution, sleepMode=nil) → error

	// --- Run the self-test once before trusting the readings ---
	// The self-test applies an internal force and checks that every axis
	// moves; a failure means the readings cannot be trusted.
	ok, dx, dy, dz, _ := chip.SelfTest() // Run the self-test, () → (bool, int32, int32, int32, error)
	fmt.Printf("self-test: ok=%v delta=(%d,%d,%d)\n", ok, dx, dy, dz)

	// --- Arm the detectors ---
	// Orientation with a 100 ms debounce rejects slow drift; a double tap
	// on Z with a 1 g threshold and latched status; freefall below 0.2 g
	// with a 60 ms debounce.
	_ = chip.SetOrientation(true, 100, 45, 4, 4, 1)                                              // Set orientation detector, (enabled=true, debounceMs=100, thresholdDeg=45, hysteresis=4, zLock=4, backFront=1) → error
	_ = chip.SetPulse(0, 0x04, [3]float32{0.5, 0.5, 1.0}, 40, 100, 300, true, false, true, true) // Set tap detector, (single=0, double=0x04 Z, thresholdG=(0.5,0.5,1.0), timeLimitMs=40, latencyMs=100, windowMs=300, latch=true, doubleAbort=false, lpf=true, hpf=true) → error
	_ = chip.SetFreefallMotion(false, 0x07, 0.2, 60, true, false)                                // Set freefall/motion detector, (motion=false, axes=0x07, thresholdG=0.2, debounceMs=60, latch=true, debounceClear=false) → error

	// --- Auto-SLEEP after 10 s of inactivity ---
	// The device drops to 12.5 Hz while idle and is woken by a tap or a
	// freefall event.
	_ = chip.SetAutoSleep(true, 12.5, 10, accelerometer.MMA8451QSourcePulse|accelerometer.MMA8451QSourceFFMT) // Set auto-sleep, (enabled=true, sleepRateHz=12.5, timeoutS=10, wakeSources=PULSE|FF_MT) → error

	// --- Route the three detectors to INT1 ---
	_ = chip.EnableInterrupt(accelerometer.MMA8451QSourceOrientation, 1) // Enable interrupt source, (source=ORIENTATION, pin=1) → error
	_ = chip.EnableInterrupt(accelerometer.MMA8451QSourcePulse, 1)       // Enable interrupt source, (source=PULSE, pin=1) → error
	_ = chip.EnableInterrupt(accelerometer.MMA8451QSourceFFMT, 1)        // Enable interrupt source, (source=FF_MT, pin=1) → error

	// --- Keep the last 16 samples in the FIFO ---
	// A circular FIFO with a 16-sample watermark lets the application
	// review the acceleration history after an event.
	_ = chip.SetFIFO(1, 16) // Set FIFO mode, (mode=1 circular, watermark=16) → error

	// --- Run for 60 s, printing the acceleration and the system mode ---
	start := time.Now()
	for time.Since(start) < 60*time.Second {
		x, y, z, _ := chip.Read()        // Read acceleration, () → (float32 g, float32 g, float32 g, error)
		mode, _ := chip.ReadSystemMode() // Read system mode, () → (uint8, error)
		state := "WAKE"
		if mode == accelerometer.MMA8451QModeSleep {
			state = "SLEEP"
		}
		fmt.Printf("%5d  x=%+.3f  y=%+.3f  z=%+.3f  %s\n",
			int(time.Since(start).Seconds()), x, y, z, state)

		// --- Act on the pending interrupts ---
		status, _ := chip.PollInterrupt() // Poll interrupt status, () → (uint8, error)
		if status&accelerometer.MMA8451QSourceOrientation != 0 {
			_, _, lapo, back, _ := chip.ReadOrientation() // Read orientation status, () → (bool, bool, uint8, bool, error)
			fmt.Printf("%5d  ORIENTATION: %s (back=%v)\n",
				int(time.Since(start).Seconds()), orientationName(lapo), back)
		}
		if status&accelerometer.MMA8451QSourcePulse != 0 {
			src, _ := chip.ReadPulseSource() // Read tap source, () → (uint8, error)
			fmt.Printf("%5d  DOUBLE TAP (src=0x%02X)\n", int(time.Since(start).Seconds()), src)
		}
		if status&accelerometer.MMA8451QSourceFFMT != 0 {
			src, _ := chip.ReadMotionSource() // Read freefall/motion source, () → (uint8, error)
			fmt.Printf("%5d  FREE FALL (src=0x%02X)\n", int(time.Since(start).Seconds()), src)
		}
		time.Sleep(250 * time.Millisecond)
	}

	// --- Review the FIFO contents ---
	// The circular FIFO still holds the samples that were not overwritten;
	// their mean shows the average acceleration over the last 16 samples.
	samples, _ := chip.ReadFIFO(-1) // Drain the FIFO, (count=-1) → ([][]float32, error)
	if len(samples) > 0 {
		var mx, my, mz float64
		for _, s := range samples {
			mx += float64(s[0])
			my += float64(s[1])
			mz += float64(s[2])
		}
		n := float64(len(samples))
		fmt.Printf("fifo: %d samples, mean=(%.3f, %.3f, %.3f) g\n",
			len(samples), mx/n, my/n, mz/n)
	} else {
		fmt.Println("fifo: empty")
	}
	fmt.Println("done")
}

// orientationName maps the LAPO code to a human-readable orientation.
func orientationName(lapo uint8) string {
	switch lapo {
	case 0:
		return "portrait up"
	case 1:
		return "portrait down"
	case 2:
		return "landscape right"
	case 3:
		return "landscape left"
	}
	return "unknown"
}
