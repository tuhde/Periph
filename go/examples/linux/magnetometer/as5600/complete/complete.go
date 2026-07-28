//go:build linux && !tinygo

// AS5600 complete example — Linux host.
//
// Exercises every method in the As5600Full API: status, magnet checks, all
// four angle readings, diagnostics, position configuration, configure, and
// burn count.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/magnetometer"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		panic(err)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x36"), 0, 8)
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x36) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	chip, err := magnetometer.NewAs5600Full(conn) // Create AS5600 driver, (connection) → (*As5600Full, error)
	if err != nil {
		panic(err)
	}

	// --- Status and magnet checks ---
	md, err := chip.IsMagnetDetected() // Check magnet present, () → (bool, error)
	if err != nil {
		panic(err)
	}
	fmt.Println("magnet_detected:", md)
	mh, err := chip.IsMagnetTooStrong() // Check magnet too strong, () → (bool, error)
	if err != nil {
		panic(err)
	}
	fmt.Println("magnet_too_strong:", mh)
	ml, err := chip.IsMagnetTooWeak() // Check magnet too weak, () → (bool, error)
	if err != nil {
		panic(err)
	}
	fmt.Println("magnet_too_weak:", ml)
	sb, err := chip.StatusByte() // Read raw status, () → (uint8, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("status_byte: 0x%02X\n", sb)

	// --- Angle readings ---
	a, err := chip.Angle() // Read absolute angle, () → (float64 degrees, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("angle: %.2f\n", a)
	r, err := chip.AngleRaw() // Read scaled angle count, () → (uint16 0-4095, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("angle_raw: %d\n", r)
	ra, err := chip.RawAngle() // Read raw unscaled angle, () → (uint16 0-4095, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("raw_angle: %d\n", ra)
	rad, err := chip.RawAngleDegrees() // Read raw angle in degrees, () → (float64 degrees, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("raw_angle_degrees: %.2f\n", rad)

	// --- Diagnostics ---
	g, err := chip.AGC() // Read AGC value, () → (uint8, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("agc: %d\n", g)
	mag, err := chip.Magnitude() // Read CORDIC magnitude, () → (uint16, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("magnitude: %d\n", mag)

	// --- Position configuration (volatile) ---
	if err := chip.SetZeroPosition(0); err != nil { // Set zero position, (pos 0-4095) → error
		panic(err)
	}
	zp, err := chip.ZeroPosition() // Read ZPOS, () → (uint16 0-4095, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("zero_position: %d\n", zp)
	if err := chip.SetMaxPosition(4095); err != nil { // Set max position, (pos 0-4095) → error
		panic(err)
	}
	mp, err := chip.MaxPosition() // Read MPOS, () → (uint16 0-4095, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("max_position: %d\n", mp)
	if err := chip.SetMaxAngle(2048); err != nil { // Set max angle span, (span 0-4095) → error
		panic(err)
	}
	ma, err := chip.MaxAngle() // Read MANG, () → (uint16 0-4095, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("max_angle: %d\n", ma)

	// --- Configure power mode and output ---
	if err := chip.Configure(0, 0, 0, 0, 0, 0, false); err != nil { // Configure chip, (pm 0-3, hyst 0-3, outs 0-2, pwmf 0-3, sf 0-3, fth 0-7, wd bool) → error
		panic(err)
	}
	// sets all fields, preserving reserved bits in CONF_H

	// --- Burn count ---
	bc, err := chip.BurnCount() // Read burn count, () → (uint8 0-3, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("burn_count: %d\n", bc)
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
