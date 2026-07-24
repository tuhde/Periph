//go:build linux && !tinygo

// AS5600 hardware test — Linux host.
//
// Reads from /dev/i2c-N and runs the AS5600 check sequence. Prints
// PASS/FAIL per check and ends with the standard ===DONE: ... === line.
// Exits 0 on full pass, 1 on any failure.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/magnetometer"
	"github.com/tuhde/Periph/go/periph/transport"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_BUS:", err)
		os.Exit(2)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x36"), 0, 8)
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_ADDR:", err)
		os.Exit(2)
	}

	tr, err := transport.NewI2CTransport(bus, uint8(addr))
	if err != nil {
		fmt.Fprintln(os.Stderr, "transport:", err)
		os.Exit(2)
	}
	defer tr.Close()

	chip, err := magnetometer.NewAs5600Full(tr)
	if err != nil {
		fmt.Fprintln(os.Stderr, "new:", err)
		os.Exit(2)
	}

	passed, failed := 0, 0
	check := func(label string, cond bool) {
		if cond {
			fmt.Println("PASS", label)
			passed++
		} else {
			fmt.Println("FAIL", label)
			failed++
		}
	}

	// --- Magnet detection ---
	md, err := chip.IsMagnetDetected()
	check("magnet_detected", err == nil && md)

	// --- Angle readings ---
	a, err := chip.Angle()
	check("angle_range", err == nil && a >= 0.0 && a < 360.0)
	r, err := chip.AngleRaw()
	check("angle_raw_range", err == nil && r <= 4095)
	ra, err := chip.RawAngle()
	check("raw_angle_range", err == nil && ra <= 4095)
	rad, err := chip.RawAngleDegrees()
	check("raw_angle_degrees_range", err == nil && rad >= 0.0 && rad < 360.0)

	// --- Diagnostics ---
	_, err = chip.AGC()
	check("agc_ok", err == nil)
	mag, err := chip.Magnitude()
	check("magnitude_non_negative", err == nil && mag >= 0)

	// --- Status ---
	sb, err := chip.StatusByte()
	check("status_byte_valid", err == nil && sb <= 255)

	// --- Position configuration (volatile) ---
	if err := chip.SetZeroPosition(100); err != nil {
		fmt.Fprintln(os.Stderr, "set_zero:", err)
	}
	zp, _ := chip.ZeroPosition()
	check("zero_position_after_set", zp == 100)

	if err := chip.SetMaxPosition(2000); err != nil {
		fmt.Fprintln(os.Stderr, "set_max:", err)
	}
	mp, _ := chip.MaxPosition()
	check("max_position_after_set", mp == 2000)

	if err := chip.SetMaxAngle(2048); err != nil {
		fmt.Fprintln(os.Stderr, "set_max_angle:", err)
	}
	ma, _ := chip.MaxAngle()
	check("max_angle_after_set", ma == 2048)

	// --- Configure ---
	if err := chip.Configure(0, 0, 0, 0, 0, 0, false); err != nil {
		fmt.Fprintln(os.Stderr, "configure:", err)
	}
	md2, _ := chip.IsMagnetDetected()
	check("configure_accepted", md2)

	// --- Burn count ---
	bc, err := chip.BurnCount()
	check("burn_count_range", err == nil && bc <= 3)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
	if failed != 0 {
		os.Exit(1)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
