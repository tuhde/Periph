//go:build tinygo

// LSM303 complete example — TinyGo (Raspberry Pi Pico W).

package main

import (
	"machine"

	"github.com/tuhde/Periph/go/periph/chips/accelerometer"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	machine.I2C0.Configure(machine.I2CConfig{Frequency: 400 * machine.KHz})
	accelConn, err := connection.NewI2CConnection(0, 0x19, nil, nil)
	if err != nil {
		panic(err)
	}
	defer accelConn.Close()
	magConn, err := connection.NewI2CConnection(0, 0x1E, nil, nil)
	if err != nil {
		panic(err)
	}
	defer magConn.Close()
	chip, err := accelerometer.NewLSM303Full(accelConn, magConn) // Create LSM303 Full driver, (accelConn, magConn) → (*LSM303Full, error)
	if err != nil {
		panic(err)
	}

	// --- Accelerometer configuration ---
	if err := chip.SetRange(8); err != nil {
		panic(err)
	} // Set accelerometer range, (rangeG) → error g
	if err := chip.SetMode(accelerometer.LSM303ModeNormal); err != nil {
		panic(err)
	} // Set accelerometer mode, (mode) → error
	if err := chip.SetDataRate(400); err != nil {
		panic(err)
	} // Set accelerometer data rate, (rateHz) → error Hz
	if err := chip.SetAxes(true, true, true); err != nil {
		panic(err)
	} // Enable accelerometer axes, (x, y, z) → error
	if err := chip.PowerDown(); err != nil {
		panic(err)
	} // Stop accelerometer conversions, () → error
	if err := chip.SetDataRate(100); err != nil {
		panic(err)
	} // Set accelerometer data rate, (rateHz) → error Hz
	if err := chip.SetMode(accelerometer.LSM303ModeHighResolution); err != nil {
		panic(err)
	} // Set accelerometer mode, (mode) → error
	if err := chip.SetRange(2); err != nil {
		panic(err)
	} // Set accelerometer range, (rangeG) → error g

	// --- Reads ---
	ax, ay, az, err := chip.ReadAcceleration() // Read 3-axis acceleration, () → (g, g, g, error)
	if err != nil {
		panic(err)
	}
	rx, ry, rz, err := chip.ReadAccelerationRaw() // Read raw acceleration counts, () → (LSB, LSB, LSB, error)
	if err != nil {
		panic(err)
	}
	ready, err := chip.NewDataAvailable() // Check new acceleration data, () → (bool, error)
	if err != nil {
		panic(err)
	}
	status, err := chip.ReadStatus() // Read accelerometer status, () → (uint8, error)
	if err != nil {
		panic(err)
	}

	// --- High-pass filter ---
	if err := chip.SetHighPass(1, 2, true, true, false, true); err != nil {
		panic(err)
	} // Configure high-pass filter, (...)
	reference, err := chip.ResetHighPass() // Reset high-pass filter, () → (uint8, error)
	if err != nil {
		panic(err)
	}
	if err := chip.SetReference(0x40); err != nil {
		panic(err)
	} // Set high-pass reference, (value) → error

	// --- FIFO ---
	if err := chip.SetFIFO(accelerometer.LSM303FIFOFIFO, 16, false); err != nil {
		panic(err)
	} // Configure FIFO, (mode, watermark, triggerInt2) → error
	level, err := chip.FIFOLevel() // Read FIFO level, () → (int, error)
	if err != nil {
		panic(err)
	}
	wm, ov, empty, lvl, err := chip.ReadFIFOStatus() // Read FIFO status, () → (bool, bool, bool, int, error)
	if err != nil {
		panic(err)
	}
	samples, err := chip.ReadFIFO(-1) // Drain FIFO, (count) → ([][3]float64, error)
	if err != nil {
		panic(err)
	}
	if err := chip.SetFIFO(accelerometer.LSM303FIFOBypass, 0, false); err != nil {
		panic(err)
	} // Configure FIFO, (mode, watermark, triggerInt2) → error

	// --- Inertial interrupt generators ---
	if err := chip.SetMotionInterrupt(1, 0x3F, 0.5, 30, true); err != nil {
		panic(err)
	} // Configure motion interrupt, (...)
	if err := chip.SetFreeFall(1, 0.35, 30); err != nil {
		panic(err)
	} // Configure free-fall, (...)
	if err := chip.SetOrientation(1, false, false); err != nil {
		panic(err)
	} // Configure orientation, (...)
	if err := chip.SetLatch(1, true); err != nil {
		panic(err)
	} // Latch generator 1, (generator, enabled) → error
	src, err := chip.ReadInterruptSource(1) // Read interrupt source, (generator) → (uint8, error)
	if err != nil {
		panic(err)
	}
	if err := chip.SetLatch(1, false); err != nil {
		panic(err)
	} // Latch generator 1, (generator, enabled) → error

	// --- Click detection ---
	if err := chip.SetClick(0x01, 0x04, 0.5, 10, 20, 50); err != nil {
		panic(err)
	} // Configure click detection, (...)
	click, err := chip.ReadClickSource() // Read click source, () → (uint8, error)
	if err != nil {
		panic(err)
	}

	// --- Interrupt routing ---
	if err := chip.SetInterruptPolarity(false); err != nil {
		panic(err)
	} // Set interrupt polarity, (activeLow) → error
	if err := chip.EnableInterrupt(accelerometer.SourceDataReady, 1); err != nil {
		panic(err)
	} // Enable interrupt source, (source, pin) → error
	if err := chip.EnableInterrupt(accelerometer.SourceClick, 1); err != nil {
		panic(err)
	} // Enable interrupt source, (source, pin) → error
	if err := chip.DisableInterrupt(accelerometer.SourceDataReady, 1); err != nil {
		panic(err)
	} // Disable interrupt source, (source, pin) → error
	isr, err := chip.PollInterrupt() // Poll interrupt status, () → (uint16, error)
	if err != nil {
		panic(err)
	}

	// --- Magnetometer ---
	if err := chip.SetMagRange(470); err != nil {
		panic(err)
	} // Set magnetometer range, (rangeUT) → error µT
	if err := chip.SetMagDataRate(30); err != nil {
		panic(err)
	} // Set magnetometer data rate, (rateHz) → error Hz
	if err := chip.SetMagMode(1); err != nil {
		panic(err)
	} // Set magnetometer mode, (mode) → error
	if err := chip.SetMagMode(0); err != nil {
		panic(err)
	} // Set magnetometer mode, (mode) → error
	mrx, mry, mrz, err := chip.ReadMagneticRaw() // Read raw magnetic counts, () → (LSB, LSB, LSB, error)
	if err != nil {
		panic(err)
	}
	mx, my, mz, err := chip.ReadMagnetic() // Read 3-axis magnetic field, () → (µT, µT, µT, error)
	if err != nil {
		panic(err)
	}
	mready, err := chip.MagDataReady() // Check new magnetometer data, () → (bool, error)
	if err != nil {
		panic(err)
	}
	drdy, lock, err := chip.ReadMagStatus() // Read magnetometer status, () → (bool, bool, error)
	if err != nil {
		panic(err)
	}
	id, err := chip.ReadMagIdentity() // Read magnetometer identity, () → ([]byte, error)
	if err != nil {
		panic(err)
	}
	if err := chip.EnableTemperature(true); err != nil {
		panic(err)
	} // Enable temperature sensor, (enabled) → error
	temp, err := chip.ReadTemperatureDelta() // Read relative temperature, () → (float64, error) °C
	if err != nil {
		panic(err)
	}
	heading, err := chip.Heading(true) // Compute compass heading, (tiltCompensate) → (float64, error) deg
	if err != nil {
		panic(err)
	}
	flat, err := chip.Heading(false) // Compute compass heading, (tiltCompensate) → (float64, error) deg
	if err != nil {
		panic(err)
	}

	// --- System ---
	if err := chip.Reboot(); err != nil {
		panic(err)
	} // Reboot accelerometer memory, () → error

	println("accel=", ax, ay, az, "raw=", rx, ry, rz, "ready=", ready, "status=", status)
	println("reference=", reference, "fifo level=", level, "wm=", wm, "ov=", ov, "empty=", empty, "lvl=", lvl, "samples=", len(samples))
	println("src=", src, "click=", click, "isr=", isr)
	println("mag=", mx, my, mz, "raw=", mrx, mry, mrz, "ready=", mready, "drdy=", drdy, "lock=", lock)
	println("temp=", temp, "heading=", heading, "flat=", flat)

}
