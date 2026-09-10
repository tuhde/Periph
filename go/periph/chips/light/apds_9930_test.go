package light

import (
	"testing"
)

func cw9930(reg byte) byte { return apds9930CmdWrite(reg) }
func cr9930(reg byte) byte { return apds9930CmdRead(reg) }

func TestAPDS9930MinimalConstruction(t *testing.T) {
	conn := newMockConnection()
	conn.setRegister(cr9930(apds9930RegID), 0x39) // ID register
	conn.setRegister(cr9930(apds9930RegCONTROL), 0x00)
	conn.setRegister(cr9930(apds9930RegATIME), 0xDB)
	conn.setRegister(cr9930(apds9930RegPTIME), 0xFF)
	conn.setRegister(cr9930(apds9930RegPPULSE), 0x08)
	conn.setRegister(cr9930(apds9930RegCONFIG), 0x00)
	conn.setRegister(cr9930(apds9930RegCH0DATAL), 0x00, 0x00)
	conn.setRegister(cr9930(apds9930RegCH1DATAL), 0x00, 0x00)
	conn.setRegister(cr9930(apds9930RegPDATAL), 0x00, 0x00)

	chip, err := NewAPDS9930Minimal(conn)
	if err != nil {
		t.Fatalf("NewAPDS9930Minimal: %v", err)
	}
	if conn.registers[cw9930(apds9930RegATIME)] != apds9930ATIMEDefault {
		t.Errorf("init: ATIME = 0x%02X, want 0xDB", conn.registers[cw9930(apds9930RegATIME)])
	}
	if conn.registers[cw9930(apds9930RegCONTROL)] != apds9930CONTROLDefault {
		t.Errorf("init: CONTROL = 0x%02X, want 0x20", conn.registers[cw9930(apds9930RegCONTROL)])
	}
	if conn.registers[cw9930(apds9930RegENABLE)] != apds9930ENABLEDefault {
		t.Errorf("init: ENABLE = 0x%02X, want 0x07", conn.registers[cw9930(apds9930RegENABLE)])
	}

	// Bad ID must reject construction.
	badConn := newMockConnection()
	badConn.setRegister(cr9930(apds9930RegID), 0xAB)
	if _, err := NewAPDS9930Minimal(badConn); err == nil {
		t.Errorf("expected error for bad chip ID")
	}

	_ = chip
}

func TestAPDS9930LuxAndProximity(t *testing.T) {
	conn := newMockConnection()
	conn.setRegister(cr9930(apds9930RegID), 0x39)
	conn.setRegister(cr9930(apds9930RegCONTROL), 0x00)
	conn.setRegister(cr9930(apds9930RegCONFIG), 0x00)
	conn.setRegister(cr9930(apds9930RegATIME), 0xDB)

	// Ch0 = 0x0010 (LE), Ch1 = 0x0000 → IAc > 0
	conn.setRegister(cr9930(apds9930RegCH0DATAL), 0x10, 0x00)
	conn.setRegister(cr9930(apds9930RegCH1DATAL), 0x00, 0x00)

	chip, err := NewAPDS9930Minimal(conn)
	if err != nil {
		t.Fatalf("NewAPDS9930Minimal: %v", err)
	}

	lx, err := chip.Lux()
	if err != nil {
		t.Fatalf("Lux: %v", err)
	}
	if lx <= 0 {
		t.Errorf("Lux() = %f, want > 0", lx)
	}

	// Dark channels → lux = 0
	conn.setRegister(cr9930(apds9930RegCH0DATAL), 0x00, 0x00)
	conn.setRegister(cr9930(apds9930RegCH1DATAL), 0x00, 0x00)
	lx, err = chip.Lux()
	if err != nil {
		t.Fatalf("Lux (dark): %v", err)
	}
	if lx != 0 {
		t.Errorf("Lux(dark) = %f, want 0", lx)
	}

	// Proximity: 0x1234 (LE)
	conn.setRegister(cr9930(apds9930RegPDATAL), 0x34, 0x12)
	p, err := chip.Proximity()
	if err != nil {
		t.Fatalf("Proximity: %v", err)
	}
	if p != 0x1234 {
		t.Errorf("Proximity() = 0x%X, want 0x1234", p)
	}
}

func TestAPDS9930FullAPI(t *testing.T) {
	conn := newMockConnection()
	conn.setRegister(cr9930(apds9930RegID), 0x39)
	conn.setRegister(cr9930(apds9930RegCONTROL), 0x00)
	conn.setRegister(cr9930(apds9930RegCONFIG), 0x00)
	conn.setRegister(cr9930(apds9930RegATIME), 0xDB)
	conn.setRegister(cr9930(apds9930RegPTIME), 0xFF)
	conn.setRegister(cr9930(apds9930RegPPULSE), 0x08)

	chip, err := NewAPDS9930Full(conn)
	if err != nil {
		t.Fatalf("NewAPDS9930Full: %v", err)
	}

	if err := chip.ConfigureALS(0xF6, 2, false); err != nil {
		t.Fatalf("ConfigureALS: %v", err)
	}
	if conn.registers[cw9930(apds9930RegATIME)] != 0xF6 {
		t.Errorf("ConfigureALS: ATIME = 0x%02X, want 0xF6", conn.registers[cw9930(apds9930RegATIME)])
	}
	if conn.registers[cw9930(apds9930RegCONTROL)]&0x03 != 2 {
		t.Errorf("ConfigureALS: CONTROL&0x03 = %d, want 2", conn.registers[cw9930(apds9930RegCONTROL)]&0x03)
	}

	if err := chip.ConfigureProximity(8, 1, 2, false, 0xFF); err != nil {
		t.Fatalf("ConfigureProximity: %v", err)
	}
	if conn.registers[cw9930(apds9930RegPPULSE)] != 8 {
		t.Errorf("ConfigureProximity: PPULSE = %d, want 8", conn.registers[cw9930(apds9930RegPPULSE)])
	}
	if conn.registers[cw9930(apds9930RegPTIME)] != 0xFF {
		t.Errorf("ConfigureProximity: PTIME = 0x%02X, want 0xFF", conn.registers[cw9930(apds9930RegPTIME)])
	}

	if err := chip.ConfigureWait(0x80, true); err != nil {
		t.Fatalf("ConfigureWait: %v", err)
	}
	if conn.registers[cw9930(apds9930RegWTIME)] != 0x80 {
		t.Errorf("ConfigureWait: WTIME = 0x%02X, want 0x80", conn.registers[cw9930(apds9930RegWTIME)])
	}
	if conn.registers[cw9930(apds9930RegCONFIG)]&0x02 == 0 {
		t.Errorf("ConfigureWait: WLONG not set")
	}

	if err := chip.DisableWait(); err != nil {
		t.Fatalf("DisableWait: %v", err)
	}
	if conn.registers[cw9930(apds9930RegENABLE)]&0x08 != 0 {
		t.Errorf("DisableWait: WEN not cleared")
	}

	conn.setRegister(cr9930(apds9930RegSTATUS), 0x01) // AVALID
	st, err := chip.Status()
	if err != nil {
		t.Fatalf("Status: %v", err)
	}
	if !st.AVALID || st.PVALID {
		t.Errorf("Status() = %+v, want AVALID=true PVALID=false", st)
	}

	if err := chip.SetAlsThresholds(100, 60000, 1); err != nil {
		t.Fatalf("SetAlsThresholds: %v", err)
	}
	if conn.registers[cw9930(apds9930RegENABLE)]&0x10 == 0 {
		t.Errorf("SetAlsThresholds: AIEN not set")
	}

	if err := chip.SetProximityThresholds(10, 200, 1); err != nil {
		t.Fatalf("SetProximityThresholds: %v", err)
	}
	if conn.registers[cw9930(apds9930RegENABLE)]&0x20 == 0 {
		t.Errorf("SetProximityThresholds: PIEN not set")
	}

	if err := chip.SetProximityOffset(-50); err != nil {
		t.Fatalf("SetProximityOffset: %v", err)
	}
	if conn.registers[cw9930(apds9930RegPOFFSET)] != 0x32 {
		t.Errorf("SetProximityOffset: POFFSET = 0x%02X, want 0x32 (-50 sign-magnitude)",
			conn.registers[cw9930(apds9930RegPOFFSET)])
	}

	if err := chip.SleepAfterInterrupt(true); err != nil {
		t.Fatalf("SleepAfterInterrupt(true): %v", err)
	}
	if conn.registers[cw9930(apds9930RegENABLE)]&0x40 == 0 {
		t.Errorf("SleepAfterInterrupt(true): SAI not set")
	}
	if err := chip.SleepAfterInterrupt(false); err != nil {
		t.Fatalf("SleepAfterInterrupt(false): %v", err)
	}
	if conn.registers[cw9930(apds9930RegENABLE)]&0x40 != 0 {
		t.Errorf("SleepAfterInterrupt(false): SAI not cleared")
	}

	// ClearInterrupt: last write must be 0xE7 (both), 0xE6 (ALS), or 0xE5 (proximity).
	if err := chip.ClearInterrupt(2); err != nil {
		t.Fatalf("ClearInterrupt(2): %v", err)
	}
	if last := conn.writes[len(conn.writes)-1]; len(last) != 1 || last[0] != 0xE5 {
		t.Errorf("ClearInterrupt(2): last write = %v, want [0xE5]", last)
	}
	if err := chip.ClearInterrupt(1); err != nil {
		t.Fatalf("ClearInterrupt(1): %v", err)
	}
	if last := conn.writes[len(conn.writes)-1]; len(last) != 1 || last[0] != 0xE6 {
		t.Errorf("ClearInterrupt(1): last write = %v, want [0xE6]", last)
	}
	if err := chip.ClearInterrupt(0); err != nil {
		t.Fatalf("ClearInterrupt(0): %v", err)
	}
	if last := conn.writes[len(conn.writes)-1]; len(last) != 1 || last[0] != 0xE7 {
		t.Errorf("ClearInterrupt(0): last write = %v, want [0xE7]", last)
	}
}