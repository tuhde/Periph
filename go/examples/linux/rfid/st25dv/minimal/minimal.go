//go:build linux && !tinygo

// ST25DV minimal example — Linux host.
//
// Constructs the driver, writes "Hello NFC" to user memory and reads it back once per second.
// The ST25DV answers on two addresses: 0x53 (user memory, dynamic registers,
// mailbox) and 0x57 (system area), both with 16-bit register addresses.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/rfid"
	"github.com/tuhde/Periph/go/periph/connection"
)

func chk(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	chk(err)

	user, err := connection.NewI2CConnectionWithWidth(bus, 0x53, 2, nil, nil) // Create user-area connection, (bus=1, addr=0x53, regBytes=2) → (*I2CConnection, error)
	chk(err)
	defer user.Close()
	system, err := connection.NewI2CConnectionWithWidth(bus, 0x57, 2, nil, nil) // Create system-area connection, (bus=1, addr=0x57, regBytes=2) → (*I2CConnection, error)
	chk(err)
	defer system.Close()

	chip, err := rfid.NewST25DVMinimal(user, system, false) // Create ST25DV driver, (user, system, gpoActiveHigh=false) → (*ST25DVMinimal, error)
	chk(err)

	chk(chip.Write(0x0100, []byte("Hello NFC"))) // Write user memory, (address, data) → error
	for {
		b, err := chip.Read(0x0100, 9) // Read user memory, (address, length) → ([]byte, error)
		chk(err)
		fmt.Printf("Memory: %s\n", b)
		time.Sleep(time.Second)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
