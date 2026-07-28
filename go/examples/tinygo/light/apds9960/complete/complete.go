//go:build tinygo

// APDS-9960 complete example — TinyGo / Raspberry Pi Pico W.
//
// Exercises the full APDS-9960 API: chip ID, RGBC reads, ALS
// configuration, wait engine, proximity, thresholds, interrupts,
// gesture engine, and status flags.
package main

import (
	"fmt"
	"machine"

	"github.com/tuhde/Periph/go/periph/connection"
	"github.com/tuhde/Periph/go/periph/chips/light"
)

func main() {
	i2c := machine.I2C1
	if err := i2c.Configure(machine.I2CConfig{
		SDA:       machine.GP4,
		SCL:       machine.GP5,
		Frequency: 400_000,
	}); err != nil {
		panic(err)
	}

	conn := connection.NewI2CConnection(i2c, 0x39, nil, nil)              // Create I2C connection, (i2c, addr=0x39) → *I2CConnection
	chip, err := light.NewAPDS9960Full(conn)                  // Create APDS-9960 full driver, (connection) → (*APDS9960Full, error)
	if err != nil {
		panic(err)
	}

	id, err := chip.ChipID() // Read device ID, () → (uint8, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("chip_id: 0x%02X\n", id) // expect 0xAB

	c, r, g, b, err := chip.Color() // Read all four RGBC channels, () → (uint16×4, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("C=%d R=%d G=%d B=%d\n", c, r, g, b)

	if err := chip.ConfigureALS(0xB6, 1); err != nil { // Configure ALS, (atime, again) → error
		panic(err)
	}
	if err := chip.ConfigureWait(0xFF, false); err != nil { // Configure wait, (wtime, long) → error
		panic(err)
	}
	if err := chip.EnableWait(true); err != nil { // Enable wait engine, (enabled=true) → error
		panic(err)
	}

	if err := chip.EnableProximity(true); err != nil { // Enable proximity, (enabled=true) → error
		panic(err)
	}
	if err := chip.ConfigureProximityLED(0, 0, 0, 1); err != nil { // Configure proximity LED, (ldrive, pgain, ppulse, pplen) → error
		panic(err)
	}
	if err := chip.SetLEDBoost(0); err != nil { // Set LED boost, (boost=0) → error
		panic(err)
	}
	prox, err := chip.Proximity() // Read proximity count, () → (uint8, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("proximity: %d\n", prox)

	if err := chip.AlsThreshold(100, 60000); err != nil { // Set ALS thresholds, (low, high) → error
		panic(err)
	}
	if err := chip.ProximityThreshold(10, 200); err != nil { // Set proximity thresholds, (low, high) → error
		panic(err)
	}
	if err := chip.SetPersistence(0, 1); err != nil { // Set persistence, (ppers, apers) → error
		panic(err)
	}

	if err := chip.EnableAlsInterrupt(true); err != nil { // Enable ALS interrupt, (enabled=true) → error
		panic(err)
	}
	if err := chip.EnableProximityInterrupt(true); err != nil { // Enable proximity interrupt, (enabled=true) → error
		panic(err)
	}
	if err := chip.ClearAlsInterrupt(); err != nil { // Clear ALS interrupt, () → error
		panic(err)
	}
	if err := chip.ClearProximityInterrupt(); err != nil { // Clear proximity interrupt, () → error
		panic(err)
	}
	if err := chip.ClearAllInterrupts(); err != nil { // Clear all non-gesture interrupts, () → error
		panic(err)
	}

	if err := chip.SetProximityOffset(10, -5); err != nil { // Set proximity offset, (ur, dl) → error
		panic(err)
	}
	if err := chip.SetProximityMask(false, false, false, false); err != nil { // Set proximity mask, (u, d, l, r) → error
		panic(err)
	}

	if err := chip.EnableGesture(true); err != nil { // Enable gesture, (enabled=true) → error
		panic(err)
	}
	if err := chip.ConfigureGesture(1, 0, 0, 1, 1, 50, 20); err != nil { // Configure gesture, (ggain, gldrive, gpulse, gplen, gwtime, gpenth, gexth) → error
		panic(err)
	}
	avail, err := chip.GestureAvailable() // Check gesture FIFO valid, () → (bool, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("gesture_available: %v\n", avail)
	level, err := chip.GestureFIFOLevel() // Read FIFO level, () → (uint8, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("gesture_fifo_level: %d\n", level)
	fifo, err := chip.ReadGestureFIFO(32) // Read gesture FIFO, (maxSets=32) → ([][4]uint8, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("gesture_fifo read: %d datasets\n", len(fifo))
	if err := chip.ClearGestureFIFO(); err != nil { // Clear gesture FIFO, () → error
		panic(err)
	}
	if err := chip.EnableGestureInterrupt(false); err != nil { // Disable gesture interrupt, (enabled=false) → error
		panic(err)
	}
	if err := chip.EnableGesture(false); err != nil { // Disable gesture, (enabled=false) → error
		panic(err)
	}

	status, err := chip.Status() // Read STATUS, () → (uint8, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("status: 0x%02X\n", status)
	validAls, err := chip.IsAlsValid() // Check AVALID, () → (bool, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("is_als_valid: %v\n", validAls)
	validProx, err := chip.IsProximityValid() // Check PVALID, () → (bool, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("is_proximity_valid: %v\n", validProx)
	satAls, err := chip.IsAlsSaturated() // Check CPSAT, () → (bool, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("is_als_saturated: %v\n", satAls)
	satProx, err := chip.IsProximitySaturated() // Check PGSAT, () → (bool, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("is_proximity_saturated: %v\n", satProx)

	if err := chip.EnableProximity(false); err != nil { // Disable proximity, (enabled=false) → error
		panic(err)
	}
}
