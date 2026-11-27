//go:build linux && !tinygo

// PN532 demo example — Linux host.
//
// Tag reader and writer: identity, card polling, NTAG / MIFARE access, raw commands,
// wake on card via OnInterrupt, RF field control and power-down.
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
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x24"), 0, 8)
	chk(err)

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x24) → (*I2CConnection, error)
	chk(err)
	defer conn.Close()

	chip, err := rfid.NewPN532Full(conn) // Create PN532 driver, (connection) → (*PN532Full, error)
	chk(err)
	chk(chip.Init()) // Wake, check firmware, SAM normal mode, finite retries, () → error

	// --- Identity and health ---
	// IC 0x32 is the PN532; the support byte lists ISO 14443 A/B and ISO 18092.
	// The communication-line self test echoes a pattern through the firmware.
	fw, err := chip.FirmwareVersion() // GetFirmwareVersion, () → (PN532FirmwareVersion, error)
	chk(err)
	fmt.Printf("PN532 IC 0x%02X firmware %d.%d support 0x%02X\n", fw.IC, fw.Version, fw.Revision, fw.Support)
	gs, err := chip.GeneralStatus() // GetGeneralStatus, () → (PN532GeneralStatus, error)
	chk(err)
	fmt.Printf("status %+v\n", gs)
	ok, err := chip.SelfTest(0x00) // Diagnose, (test=0x00) → (bool, error)
	chk(err)
	fmt.Println("self test", ok)

	// --- Card polling: 15 s at 1 Hz ---
	// Present a card or tag. The finite retry count makes every poll return
	// after a few milliseconds when the field is empty.
	for i := 0; i < 15; i++ {
		targets, err := chip.ListPassiveTargets(rfid.PN532Baud106A, 1, nil, time.Second) // InListPassiveTarget, (baud, maxTargets, initiatorData, timeout) → ([]PN532Target, error)
		chk(err)
		if len(targets) > 0 {
			t := targets[0]
			fmt.Printf("%2d s UID %X SENS_RES 0x%04X SEL_RES 0x%02X\n", i, t.UID, t.SensRes, t.SelRes)
			chk(chip.Release(t.Tg)) // InRelease, (target=0) → error
		} else {
			fmt.Printf("%2d s no card\n", i)
		}
		time.Sleep(time.Second)
	}

	// --- Tag access ---
	// SEL_RES 0x00 is an NTAG / MIFARE Ultralight: read page 4 and write it back.
	// SEL_RES 0x08 is a MIFARE Classic 1K: authenticate sector 1 with the transport
	// key FF FF FF FF FF FF, read block 4 and write it back. Writing sector trailers
	// can lock a sector forever.
	targets, err := chip.ListPassiveTargets(rfid.PN532Baud106A, 1, nil, time.Second) // InListPassiveTarget, (baud, maxTargets, initiatorData, timeout) → ([]PN532Target, error)
	chk(err)
	if len(targets) > 0 {
		t := targets[0]
		chk(chip.Deselect(t.Tg)) // InDeselect, (target=0) → error
		chk(chip.Select(t.Tg))   // InSelect, (target=0) → error
		if t.SelRes == 0x00 {
			page, err := chip.NTAGReadPage(t.Tg, 4) // NTAG READ, (target, page) → ([16]byte, error)
			if err == nil {
				fmt.Printf("pages 4..7 %X\n", page)
				_ = chip.NTAGWritePage(t.Tg, 4, [4]byte{page[0], page[1], page[2], page[3]}) // NTAG WRITE, (target, page, data [4]byte) → error
			}
			data, err := chip.DataExchange(t.Tg, []byte{0x30, 0x00}) // InDataExchange, (target, data) → ([]byte, error)
			fmt.Printf("raw read %X %v\n", data, err)
		} else if t.SelRes&0x08 != 0 {
			key := [6]byte{0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF}
			if err := chip.MifareAuthenticate(t.Tg, 4, key, t.UID, false); err != nil { // MIFARE auth, (target, block, key, uid, keyB=false) → error
				fmt.Println("authentication failed:", err)
			} else if block, err := chip.MifareReadBlock(t.Tg, 4); err == nil { // MIFARE READ, (target, block) → ([16]byte, error)
				fmt.Printf("block 4 %X\n", block)
				_ = chip.MifareWriteBlock(t.Tg, 4, block) // MIFARE WRITE, (target, block, data [16]byte) → error
			}
		}
		_ = chip.Release(t.Tg) // InRelease, (target=0) → error
	} else {
		fmt.Println("no card for the tag access part")
	}

	// --- Raw RF and raw commands ---
	// InCommunicateThru sends bytes without PN532 protocol handling; with no
	// target selected it reports an error. SendCommand reaches every command.
	thru, err := chip.CommunicateThru([]byte{0x30, 0x00}) // InCommunicateThru, (data) → ([]byte, error)
	fmt.Printf("thru %X %v\n", thru, err)
	raw, err := chip.SendCommand(0x02, nil, time.Second) // Raw command, (command, data, timeout=1 s) → ([]byte, error)
	chk(err)
	fmt.Printf("raw GetFirmwareVersion %X\n", raw)
	txMode, err := chip.ReadRegister(0x6302) // ReadRegister, (address) → (uint8, error)
	chk(err)
	fmt.Printf("CIU_TxMode 0x%02X\n", txMode)
	chk(chip.WriteRegister(0x6302, txMode)) // WriteRegister, (address, value) → error

	// --- Wake on card with OnInterrupt ---
	// Infinite activation retries make InListPassiveTarget wait for a card;
	// IRQ (or the status-byte poll) wakes the callback when the response is ready.
	chk(chip.SetMaxRetries(0xFF, 0xFF, 1)) // RFConfiguration item 5, (passiveActivation=2, atr=0xFF, psl=1) → error
	ready := make(chan struct{}, 1)
	chk(chip.OnInterrupt(func() { // Subscribe to IRQ, (callback) → error
		select {
		case ready <- struct{}{}:
		default:
		}
	}))
	chk(chip.StartCommand(0x4A, []byte{0x01, 0x00})) // Command without waiting, (command, data) → error
	fmt.Println("waiting 10 s for a card ...")
	select {
	case <-ready:
	case <-time.After(10 * time.Second):
	}
	chk(chip.OffInterrupt())                         // Unsubscribe, () → error
	if pending, _ := chip.PollInterrupt(); pending { // Frame ready?, () → (bool, error)
		if ok, _ := chip.WaitReady(10 * time.Millisecond); ok { // Wait for a frame, (timeout) → (bool, error)
			resp, err := chip.ReadResponse(0x4A, time.Second) // Read the pending response, (command, timeout=1 s) → ([]byte, error)
			if err == nil && len(resp) >= 6 {
				fmt.Printf("card arrived, UID %X\n", resp[6:6+int(resp[5])])
			}
			_ = chip.Release(0) // InRelease, (target=0) → error
		}
	} else {
		_, _ = chip.ReadResponse(0x4A, 0) // Read the pending response, (command, timeout) → ([]byte, error)
		fmt.Println("no card arrived")    // the timeout aborted the command with an ACK frame
	}
	chk(chip.SetMaxRetries(2, 0xFF, 1))   // RFConfiguration item 5, (passiveActivation=2, atr=0xFF, psl=1) → error
	uid, err := chip.ReadUID(time.Second) // Detect a type A card, (timeout=1 s) → ([]byte | nil, error)
	chk(err)
	fmt.Printf("ReadUID %X\n", uid)

	// --- RF field off and on, then power down ---
	// With the field off a card loses power; the reader draws less current.
	chk(chip.SetRFField(false, false)) // RFConfiguration item 1, (on, autoRFCA=false) → error
	time.Sleep(time.Second)
	chk(chip.SetRFField(true, false)) // RFConfiguration item 1, (on, autoRFCA=false) → error
	chk(chip.PowerDown(0x20))         // PowerDown, (wakeupSources=0x20) → error
	fmt.Println("powered down; the next I2C access wakes the chip")
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
