//go:build linux && !tinygo

// MFRC522 minimal example — Linux host.
//
// Constructs the driver with an I2C transport at address 0x28, then
// loops polling for an ISO/IEC 14443 Type A card. When a card is
// detected, the UID is read and printed.
//
// MFRC522 I2C address is 0x28 with all address pins (ADR_0..ADR_2)
// tied LOW. Set the I2C_ADDR env var to override for boards with
// non-default address-pin wiring.
package main

import (
	"encoding/hex"
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/rfid"
	"github.com/tuhde/Periph/go/periph/transport"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		panic(err)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x28"), 0, 8)
	if err != nil {
		panic(err)
	}

	tr, err := transport.NewI2CTransport(bus, uint8(addr)) // Create I2C transport, (bus=1, addr=0x28) → (*I2CTransport, error)
	if err != nil {
		panic(err)
	}
	defer tr.Close()

	chip, err := rfid.NewMFRC522Minimal(tr) // Create MFRC522 driver, (transport) → (*MFRC522Minimal, error)
	if err != nil {
		panic(err)
	}

	for {
		present, err := chip.IsCardPresent() // Probe for a card, () → (bool, error)
		if err != nil {
			fmt.Fprintln(os.Stderr, "is_card_present:", err)
			time.Sleep(time.Second)
			continue
		}
		if !present {
			fmt.Println("waiting for card...")
			time.Sleep(500 * time.Millisecond)
			continue
		}
		uid, err := chip.ReadUID() // Read card UID, () → ([]byte, error)
		if err != nil {
			fmt.Fprintln(os.Stderr, "read_uid:", err)
			time.Sleep(time.Second)
			continue
		}
		if uid == nil {
			fmt.Println("no uid (card lost during select)")
			time.Sleep(time.Second)
			continue
		}
		fmt.Printf("card uid: %s\n", hex.EncodeToString(uid))
		time.Sleep(time.Second)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
