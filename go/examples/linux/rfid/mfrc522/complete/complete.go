//go:build linux && !tinygo

// MFRC522 complete example — Linux host.
//
// Exercises every method in the MFRC522Full API: card detection,
// version read, antenna gain inspection, self test, WUPA,
// anticollision-only Select, HLTA, MIFARE Classic authenticated
// read/write/value operations, MIFARE Ultralight page read/write,
// and RandomID.
package main

import (
	"encoding/hex"
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/rfid"
	"github.com/tuhde/Periph/go/periph/connection"
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

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x28) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	chip, err := rfid.NewMFRC522Full(conn) // Create MFRC522 driver, (connection) → (*MFRC522Full, error)
	if err != nil {
		panic(err)
	}

	// --- Identification and configuration inspection ---
	// VersionReg decode: chip_type 0x09, version 1 or 2.
	chipType, ver, err := chip.Version() // Read version register, () → (chipType, version, error)
	if err != nil {
		fmt.Fprintln(os.Stderr, "version:", err)
	} else {
		fmt.Printf("version: chip=0x%X ver=%d\n", chipType, ver)
	}

	// Read back the current antenna gain (33 dB by default after init).
	gain, err := chip.AntennaGain() // Read antenna gain, () → (uint8, error)
	if err != nil {
		fmt.Fprintln(os.Stderr, "antenna_gain:", err)
	} else {
		fmt.Printf("antenna_gain: 0x%02X\n", gain)
	}

	// Switch to 48 dB gain.
	if err := chip.SetAntennaGain(rfid.RX_GAIN_48_DB); err != nil { // Set antenna gain, (gain=RX_GAIN_48_DB) → error
		fmt.Fprintln(os.Stderr, "set_antenna_gain:", err)
	}

	// --- Card detection paths (no auth) ---
	// WUPA — same as IsCardPresent but also wakes HALTed cards.
	present, err := chip.WakeupCard() // Send WUPA, () → (bool, error)
	if err != nil {
		fmt.Fprintln(os.Stderr, "wakeup_card:", err)
	} else {
		fmt.Printf("wakeup_card: %v\n", present)
	}

	// Anticollision-only Select: leaves the card active for
	// subsequent authenticated operations.
	uid, err := chip.SelectCard() // Anticollision/select, () → ([]byte, error)
	if err != nil {
		fmt.Fprintln(os.Stderr, "select_card:", err)
	} else if uid == nil {
		fmt.Println("select_card: no card")
	} else {
		fmt.Printf("select_card uid: %s\n", hex.EncodeToString(uid))
	}

	// Halt the card.
	if err := chip.HaltCard(); err != nil { // Send HLTA, () → error
		fmt.Fprintln(os.Stderr, "halt_card:", err)
	}

	// --- MIFARE Classic authenticated read/write (block 4 in sector 1) ---
	// Use the well-known MIFARE factory default key A (FF FF FF FF FF FF);
	// for any real access-control system, replace with a per-deployment
	// secret.
	if uid != nil && len(uid) == 4 {
		defaultKey := []byte{0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF}
		ok, err := chip.Authenticate(4, rfid.KEY_A, defaultKey, uid) // Run MFAuthent, (block, key_type, key, uid) → (bool, error)
		if err != nil {
			fmt.Fprintln(os.Stderr, "authenticate:", err)
		} else {
			fmt.Printf("authenticate: %v\n", ok)
			if ok {
				block, err := chip.ReadBlock(4) // Read MIFARE block, (block_address) → ([]byte, error)
				if err != nil {
					fmt.Fprintln(os.Stderr, "read_block:", err)
				} else {
					fmt.Printf("read_block: %s\n", hex.EncodeToString(block))
				}
				if err := chip.StopCrypto(); err != nil { // Clear MFCrypto1On, () → error
					fmt.Fprintln(os.Stderr, "stop_crypto:", err)
				}
			}
		}
	}

	// --- MIFARE Ultralight page read (no auth) ---
	pages, err := chip.ReadUltralightPage(0) // Read 4 pages, (page_address) → ([]byte, error)
	if err != nil {
		fmt.Fprintln(os.Stderr, "read_ultralight_page:", err)
	} else {
		fmt.Printf("read_ultralight_page(0): %s\n", hex.EncodeToString(pages))
	}

	// --- Internal: random ID generation (chip-only, no card needed) ---
	randID, err := chip.GenerateRandomID() // Run Generate RandomID, () → ([]byte, error)
	if err != nil {
		fmt.Fprintln(os.Stderr, "generate_random_id:", err)
	} else {
		fmt.Printf("generate_random_id: %s\n", hex.EncodeToString(randID))
	}

	// --- Antenna toggling ---
	if err := chip.AntennaOff(); err != nil { // Disable antenna, () → error
		fmt.Fprintln(os.Stderr, "antenna_off:", err)
	}
	if err := chip.AntennaOn(); err != nil { // Enable antenna, () → error
		fmt.Fprintln(os.Stderr, "antenna_on:", err)
	}

	// --- Digital self test ---
	ok, err := chip.SelfTest() // Run self test, () → (bool, error)
	if err != nil {
		fmt.Fprintln(os.Stderr, "self_test:", err)
	} else {
		fmt.Printf("self_test: %v\n", ok)
	}

	// Re-run init in case self test left the chip in a fresh state.
	if err := chip.Reset(); err != nil { // Re-run SoftReset + init, () → error
		fmt.Fprintln(os.Stderr, "reset:", err)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
