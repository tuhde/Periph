package power

import (
	"testing"
)

func approxEqual32(a, b, eps float32) bool {
	d := a - b
	if d < 0 {
		d = -d
	}
	return d < eps
}

// mockConnection and lastWriteTo are defined in ina226_test.go (shared
// across this package's unit tests).

func TestINA3221FullAPI(t *testing.T) {
	conn := newMockConnection()

	// Construction (default rShunt=0.1 for all 3 channels) writes nothing.
	sensor, err := NewINA3221Full(conn, 0.1)
	if err != nil {
		t.Fatalf("NewINA3221Full: %v", err)
	}
	if len(conn.writes) != 0 {
		t.Errorf("init: expected no writes, got %v", conn.writes)
	}

	// --- Channel 1 ---
	// Bus1 raw=10000 (0x2710) -> (10000>>3)*8e-3 = 10.0 V
	conn.setRegister(ina3221RegBus1, 0x27, 0x10)
	if v, err := sensor.Voltage(1); err != nil || v != 10.0 {
		t.Errorf("Voltage(1) = %v, %v, want 10.0, nil", v, err)
	}

	// Shunt1 raw signed = -400 (0xFE70) -> -400 * 5e-6 = -0.002 V
	conn.setRegister(ina3221RegShunt1, 0xFE, 0x70)
	if v, err := sensor.ShuntVoltage(1); err != nil || v != float32(-400)*5e-6 {
		t.Errorf("ShuntVoltage(1) = %v, %v, want %v, nil", v, err, float32(-400)*5e-6)
	}
	if v, err := sensor.Current(1); err != nil || !approxEqual32(v, -0.02, 1e-6) {
		t.Errorf("Current(1) = %v, %v, want -0.02, nil", v, err)
	}

	// Power(1): Shunt1 (0x01) and Bus1 (0x02) are adjacent registers, and
	// the mock's byte-slot model can't hold two independent 16-bit values
	// across adjacent addresses at once (writing one clobbers the shared
	// byte slot) - so the Shunt1 low byte and Bus1 high byte are chosen
	// equal (0x10) to survive either write order. Shunt1=0xFF10 (-240
	// signed) -> -0.0012 V; Bus1=0x1000 (4096) -> 4.096 V.
	conn.setRegister(ina3221RegShunt1, 0xFF, 0x10)
	conn.setRegister(ina3221RegBus1, 0x10, 0x00)
	if v, err := sensor.Power(1); err != nil || !approxEqual32(v, 4.096*-0.012, 1e-6) {
		t.Errorf("Power(1) = %v, %v, want %v, nil", v, err, 4.096*-0.012)
	}

	// --- Channel 2 ---
	// Bus2 raw=4096 (0x1000) -> (4096>>3)*8e-3 = 4.096 V
	conn.setRegister(ina3221RegBus2, 0x10, 0x00)
	if v, err := sensor.Voltage(2); err != nil || v != 4.096 {
		t.Errorf("Voltage(2) = %v, %v, want 4.096, nil", v, err)
	}

	// Shunt2 raw=800 (0x0320) -> 800 * 5e-6 = 0.004 V
	conn.setRegister(ina3221RegShunt2, 0x03, 0x20)
	if v, err := sensor.ShuntVoltage(2); err != nil || v != float32(800)*5e-6 {
		t.Errorf("ShuntVoltage(2) = %v, %v, want %v, nil", v, err, float32(800)*5e-6)
	}
	if v, err := sensor.Current(2); err != nil || !approxEqual32(v, 0.04, 1e-6) {
		t.Errorf("Current(2) = %v, %v, want 0.04, nil", v, err)
	}

	// Power(2): same adjacent-register overlap as Power(1); Shunt2 low byte
	// and Bus2 high byte chosen equal (0x08). Shunt2=0x0108 (264) ->
	// 0.00132 V; Bus2=0x0800 (2048) -> 2.048 V.
	conn.setRegister(ina3221RegShunt2, 0x01, 0x08)
	conn.setRegister(ina3221RegBus2, 0x08, 0x00)
	if v, err := sensor.Power(2); err != nil || !approxEqual32(v, 2.048*0.0132, 1e-6) {
		t.Errorf("Power(2) = %v, %v, want %v, nil", v, err, 2.048*0.0132)
	}

	// Invalid channel returns an error.
	if _, err := sensor.Voltage(4); err == nil {
		t.Errorf("Voltage(4): expected error for invalid channel")
	}

	// Configure(3, 2, 1, 5) preserves channel-enable bits (0x7000) from the
	// current Configuration Register.
	conn.setRegister(ina3221RegConfig, 0x71, 0x27)
	if err := sensor.Configure(3, 2, 1, 5); err != nil {
		t.Fatalf("Configure: %v", err)
	}
	if cfg := lastWriteTo(conn.writes, ina3221RegConfig); cfg == nil || cfg[1] != 0x76 || cfg[2] != 0x8D {
		t.Errorf("Configure: expected CONFIG=0x768D, got %v", cfg)
	}

	// EnableChannel(2, true): CH2en is bit 13.
	conn.setRegister(ina3221RegConfig, 0x01, 0x27)
	if err := sensor.EnableChannel(2, true); err != nil {
		t.Fatalf("EnableChannel: %v", err)
	}
	if cfg := lastWriteTo(conn.writes, ina3221RegConfig); cfg == nil || cfg[1] != 0x21 || cfg[2] != 0x27 {
		t.Errorf("EnableChannel: expected CONFIG=0x2127, got %v", cfg)
	}

	// ChannelEnabled(1): CH1en is bit 14.
	conn.setRegister(ina3221RegConfig, 0x41, 0x27)
	if v, err := sensor.ChannelEnabled(1); err != nil || !v {
		t.Errorf("ChannelEnabled(1) = %v, %v, want true, nil", v, err)
	}

	// ConversionReady(): CVRF is bit 0.
	conn.setRegister(ina3221RegMaskEn, 0x00, 0x01)
	if v, err := sensor.ConversionReady(); err != nil || !v {
		t.Errorf("ConversionReady() = %v, %v, want true, nil", v, err)
	}

	// SetCriticalAlert(2, 0.048, true): raw = (1200 << 3) & 0xFFF8 = 0x2580.
	conn.setRegister(ina3221RegMaskEn, 0x00, 0x00)
	if err := sensor.SetCriticalAlert(2, 0.048, true); err != nil {
		t.Fatalf("SetCriticalAlert: %v", err)
	}
	if c := lastWriteTo(conn.writes, ina3221RegCh2Crit); c == nil || c[1] != 0x25 || c[2] != 0x80 {
		t.Errorf("SetCriticalAlert: expected CH2_CRIT=0x2580, got %v", c)
	}
	if m := lastWriteTo(conn.writes, ina3221RegMaskEn); m == nil || m[1] != 0x04 || m[2] != 0x00 {
		t.Errorf("SetCriticalAlert: expected Mask/Enable=0x0400, got %v", m)
	}

	// SetWarningAlert(1, 0.024, false): raw = (600 << 3) & 0xFFF8 = 0x12C0.
	conn.setRegister(ina3221RegMaskEn, 0x04, 0x00)
	if err := sensor.SetWarningAlert(1, 0.024, false); err != nil {
		t.Fatalf("SetWarningAlert: %v", err)
	}
	if w := lastWriteTo(conn.writes, ina3221RegCh1Warn); w == nil || w[1] != 0x12 || w[2] != 0xC0 {
		t.Errorf("SetWarningAlert: expected CH1_WARN=0x12C0, got %v", w)
	}
	if m := lastWriteTo(conn.writes, ina3221RegMaskEn); m == nil || m[1] != 0x04 || m[2] != 0x00 {
		t.Errorf("SetWarningAlert: expected Mask/Enable=0x0400, got %v", m)
	}

	// AlertFlags(): raw Mask/Enable register.
	conn.setRegister(ina3221RegMaskEn, 0x02, 0x41)
	if v, err := sensor.AlertFlags(); err != nil || v != 0x0241 {
		t.Errorf("AlertFlags() = %v, %v, want 0x0241, nil", v, err)
	}

	// SetSummationChannels([1], 0.1) with a stale SCC3 bit (0x1000) already
	// set: the fix must clear bits 14:12 (0x7000), not just 15:13 (0xE000),
	// or SCC3 would incorrectly survive; and channel 1 must map to bit 14
	// (SCC1), not the reserved bit 15.
	conn.setRegister(ina3221RegMaskEn, 0x10, 0x00)
	if err := sensor.SetSummationChannels([]uint8{1}, 0.1); err != nil {
		t.Fatalf("SetSummationChannels: %v", err)
	}
	if m := lastWriteTo(conn.writes, ina3221RegMaskEn); m == nil || m[1] != 0x40 || m[2] != 0x00 {
		t.Errorf("SetSummationChannels: expected Mask/Enable=0x4000, got %v", m)
	}
	if s := lastWriteTo(conn.writes, ina3221RegSumLimit); s == nil || s[1] != 0x13 || s[2] != 0x88 {
		t.Errorf("SetSummationChannels: expected SumLimit=0x1388, got %v", s)
	}

	// SummationValue(): raw=0x2328 (9000) -> 9000 * 20e-6 = 0.18 V.
	conn.setRegister(ina3221RegSum, 0x23, 0x28)
	if v, err := sensor.SummationValue(); err != nil || !approxEqual32(v, 0.18, 1e-6) {
		t.Errorf("SummationValue() = %v, %v, want 0.18, nil", v, err)
	}

	// SetPowerValidLimits(8.112, 4.096): rawUpper=(1014<<3)&0xFFF8=0x1FB0,
	// rawLower=(512<<3)&0xFFF8=0x1000.
	if err := sensor.SetPowerValidLimits(8.112, 4.096); err != nil {
		t.Fatalf("SetPowerValidLimits: %v", err)
	}
	if u := lastWriteTo(conn.writes, ina3221RegPVUpper); u == nil || u[1] != 0x1F || u[2] != 0xB0 {
		t.Errorf("SetPowerValidLimits: expected PVUpper=0x1FB0, got %v", u)
	}
	if l := lastWriteTo(conn.writes, ina3221RegPVLower); l == nil || l[1] != 0x10 || l[2] != 0x00 {
		t.Errorf("SetPowerValidLimits: expected PVLower=0x1000, got %v", l)
	}

	// PowerValid(): PVF is bit 2.
	conn.setRegister(ina3221RegMaskEn, 0x00, 0x04)
	if v, err := sensor.PowerValid(); err != nil || !v {
		t.Errorf("PowerValid() = %v, %v, want true, nil", v, err)
	}

	// Shutdown(): reads CONFIG, saves MODE bits, writes CONFIG & 0xFFF8.
	conn.setRegister(ina3221RegConfig, 0x71, 0x27)
	if err := sensor.Shutdown(); err != nil {
		t.Fatalf("Shutdown: %v", err)
	}
	if last := conn.writes[len(conn.writes)-1]; last[1] != 0x71 || last[2] != 0x20 {
		t.Errorf("Shutdown: expected CONFIG=0x7120, got %v", last)
	}

	// Wake(): reads CONFIG, restores saved MODE bits.
	conn.setRegister(ina3221RegConfig, 0x71, 0x20)
	if err := sensor.Wake(); err != nil {
		t.Fatalf("Wake: %v", err)
	}
	if last := conn.writes[len(conn.writes)-1]; last[1] != 0x71 || last[2] != 0x27 {
		t.Errorf("Wake: expected CONFIG=0x7127, got %v", last)
	}

	// Reset(): writes CONFIG = 0x8000 (RST bit) only.
	if err := sensor.Reset(); err != nil {
		t.Fatalf("Reset: %v", err)
	}
	if last := conn.writes[len(conn.writes)-1]; last[1] != 0x80 || last[2] != 0x00 {
		t.Errorf("Reset: expected CONFIG=0x8000, got %v", last)
	}

	// ManufacturerID() / DieID()
	conn.setRegister(ina3221RegMfrID, 0x54, 0x49)
	if v, err := sensor.ManufacturerID(); err != nil || v != 0x5449 {
		t.Errorf("ManufacturerID() = %v, %v, want 0x5449, nil", v, err)
	}
	conn.setRegister(ina3221RegDieID, 0x32, 0x20)
	if v, err := sensor.DieID(); err != nil || v != 0x3220 {
		t.Errorf("DieID() = %v, %v, want 0x3220, nil", v, err)
	}
}
