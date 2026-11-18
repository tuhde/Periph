package power

import (
	"math"
	"testing"
)

func near32(a, b, tol float32) bool { return float32(math.Abs(float64(a-b))) < tol }

// word3 returns the 3-byte register writes (register + two data bytes) in call order.
func word3(m *mockConnection) [][]byte {
	var out [][]byte
	for _, w := range m.writes {
		if len(w) == 3 {
			out = append(out, w)
		}
	}
	return out
}

func lastWord(t *testing.T, m *mockConnection, reg, msb, lsb byte, label string) {
	t.Helper()
	w := word3(m)
	if len(w) == 0 {
		t.Fatalf("%s: no write", label)
	}
	g := w[len(w)-1]
	if g[0] != reg || g[1] != msb || g[2] != lsb {
		t.Errorf("%s: wrote % X, want % X", label, g, []byte{reg, msb, lsb})
	}
}

func TestMax17048Full(t *testing.T) {
	conn := newMockConnection()
	g, err := NewMax17048Full(conn, 1)
	if err != nil {
		t.Fatal(err)
	}
	if len(conn.writes) != 0 {
		t.Error("constructor must not touch the bus")
	}

	conn.setRegister(0x02, 0xC3, 0x50)
	if v, _ := g.Voltage(); !near32(v, 3.90625, 1e-5) {
		t.Errorf("voltage = %v", v)
	}
	if v, _ := g.RawVoltage(); v != 0xC350 {
		t.Errorf("raw voltage = %#x", v)
	}
	two, _ := NewMax17048Minimal(conn, 2)
	if v, _ := two.Voltage(); !near32(v, 7.8125, 1e-5) {
		t.Errorf("2-cell voltage = %v", v)
	}

	conn.setRegister(0x04, 0x4B, 0x80)
	if p, _ := g.Percent(); !near32(p, 75.5, 1e-5) {
		t.Errorf("percent = %v", p)
	}
	conn.setRegister(0x04, 0xFF, 0xFF)
	if p, _ := g.Percent(); p != 100 {
		t.Errorf("percent clamp = %v", p)
	}
	if v, _ := g.RawSOC(); v != 0xFFFF {
		t.Errorf("raw soc = %#x", v)
	}

	conn.setRegister(0x16, 0xFF, 0xEC)
	if r, _ := g.ChargeRate(); !near32(r, -4.16, 1e-4) {
		t.Errorf("rate = %v", r)
	}
	conn.setRegister(0x16, 0x00, 0x64)
	if r, _ := g.ChargeRate(); !near32(r, 20.8, 1e-4) {
		t.Errorf("rate = %v", r)
	}
	conn.setRegister(0x08, 0x00, 0x12)
	if v, _ := g.Version(); v != 0x12 {
		t.Errorf("version = %#x", v)
	}
	conn.setRegister(0x18, 0x96, 0xAB)
	if v, _ := g.ChipID(); v != 0xAB {
		t.Errorf("id = %#x", v)
	}

	conn.setRegister(0x0C, 0x97, 0x1C)
	if v, _ := g.GetRCOMP(); v != 0x97 {
		t.Errorf("rcomp = %#x", v)
	}
	_ = g.SetRCOMP(0x80)
	lastWord(t, conn, 0x0C, 0x80, 0x1C, "set_rcomp")
	conn.setRegister(0x0C, 0x97, 0x1C)
	_ = g.CompensateTemperature(35)
	lastWord(t, conn, 0x0C, 0x90, 0x1C, "compensate hot")
	_ = g.CompensateTemperature(5)
	lastWord(t, conn, 0x0C, 0xE2, 0x1C, "compensate cold")

	conn.setRegister(0x0C, 0x97, 0x1C)
	_ = g.SetAlertSOC(10)
	lastWord(t, conn, 0x0C, 0x97, 0x16, "set_alert_soc")
	if v, _ := g.GetAlertSOC(); v != 10 {
		t.Errorf("alert soc = %d", v)
	}
	_ = g.SetSOCChangeAlert(true)
	lastWord(t, conn, 0x0C, 0x97, 0x56, "soc change on")
	_ = g.SetSOCChangeAlert(false)
	lastWord(t, conn, 0x0C, 0x97, 0x16, "soc change off")

	if err := g.SetVoltageAlert(3.3, 4.2); err != nil {
		t.Fatal(err)
	}
	lastWord(t, conn, 0x14, 0xA5, 0xD2, "voltage alert")
	if lo, hi, _ := g.GetVoltageAlert(); !near32(lo, 3.3, 1e-4) || !near32(hi, 4.2, 1e-4) {
		t.Errorf("window = %v %v", lo, hi)
	}
	_ = g.SetVoltageAlert(0, 5.1)
	lastWord(t, conn, 0x14, 0x00, 0xFF, "voltage alert disabled")
	n := len(conn.writes)
	if err := g.SetVoltageAlert(4, 3); err != ErrMax17048InvalidArgument || len(conn.writes) != n {
		t.Error("invalid window must be rejected without a write")
	}

	conn.setRegister(0x18, 0x01, 0x5A)
	_ = g.SetResetVoltage(3.0)
	lastWord(t, conn, 0x18, 0x97, 0x5A, "reset voltage")
	if v, _ := g.GetResetVoltage(); !near32(v, 3.0, 1e-4) {
		t.Errorf("reset voltage = %v", v)
	}
	_ = g.SetComparatorDisabled(false)
	lastWord(t, conn, 0x18, 0x96, 0x5A, "comparator enabled")
	_ = g.SetComparatorDisabled(true)
	lastWord(t, conn, 0x18, 0x97, 0x5A, "comparator disabled")

	conn.setRegister(0x1A, 0x01, 0x00)
	_ = g.EnableVoltageResetAlert(true)
	lastWord(t, conn, 0x1A, 0x41, 0x00, "envr on")
	_ = g.EnableVoltageResetAlert(false)
	lastWord(t, conn, 0x1A, 0x01, 0x00, "envr off")

	_ = g.SetHibernateThresholds(26.624, 0.06)
	lastWord(t, conn, 0x0A, 0x80, 0x30, "hibernate thresholds")
	if h, a, _ := g.GetHibernateThresholds(); !near32(h, 26.624, 1e-3) || !near32(a, 0.06, 1e-5) {
		t.Errorf("hibernate = %v %v", h, a)
	}
	_ = g.DisableHibernate()
	lastWord(t, conn, 0x0A, 0x00, 0x00, "disable hibernate")
	_ = g.ForceHibernate()
	lastWord(t, conn, 0x0A, 0xFF, 0xFF, "force hibernate")

	conn.setRegister(0x06, 0x10, 0x00)
	if !g.IsHibernating() {
		t.Error("hibernating")
	}
	conn.setRegister(0x06, 0x00, 0x00)
	if g.IsHibernating() {
		t.Error("not hibernating")
	}
	_ = g.EnableSleep(true)
	lastWord(t, conn, 0x06, 0x20, 0x00, "enable sleep")
	_ = g.QuickStart()
	lastWord(t, conn, 0x06, 0x60, 0x00, "quick start keeps EnSleep")
	_ = g.EnableSleep(false)
	_ = g.QuickStart()
	lastWord(t, conn, 0x06, 0x40, 0x00, "quick start")

	conn.setRegister(0x0C, 0x97, 0x1C)
	_ = g.Sleep()
	lastWord(t, conn, 0x0C, 0x97, 0x9C, "sleep")
	_ = g.Wake()
	lastWord(t, conn, 0x0C, 0x97, 0x1C, "wake")

	_ = g.WriteConfig(0x9700)
	lastWord(t, conn, 0x0C, 0x97, 0x00, "write config")
	if c, _ := g.ReadConfig(); c != 0x9700 {
		t.Errorf("config = %#x", c)
	}

	conn.setRegister(0x1A, 0x11, 0x00)
	conn.setRegister(0x0C, 0x97, 0x3C)
	_ = g.ClearStatus(0x0100)
	if conn.registers[0x1A] != 0x10 {
		t.Errorf("status = %#x", conn.registers[0x1A])
	}
	if conn.registers[0x0D] != 0x1C {
		t.Errorf("ALRT not cleared: %#x", conn.registers[0x0D])
	}

	conn.setRegister(0x1A, 0x51, 0x00)
	conn.setRegister(0x0C, 0x97, 0x3C)
	if s, _ := g.PollInterrupt(); s != 0x1100 {
		t.Errorf("poll = %#x", s)
	}
	if conn.registers[0x1A] != 0x40 || conn.registers[0x0D] != 0x1C {
		t.Error("poll must clear the flags and ALRT")
	}
	n = len(word3(conn))
	if s, _ := g.PollInterrupt(); s != 0 || len(word3(conn)) != n {
		t.Error("poll with no flags must not write")
	}

	conn.setRegister(0x0C, 0x97, 0x1C)
	_ = g.EnableInterrupt(Max17048SourceSOCChange)
	lastWord(t, conn, 0x0C, 0x97, 0x5C, "enable soc change")
	_ = g.DisableInterrupt(Max17048SourceSOCChange)
	lastWord(t, conn, 0x0C, 0x97, 0x1C, "disable soc change")
	conn.setRegister(0x14, 0xA5, 0xD2)
	_ = g.DisableInterrupt(Max17048SourceUndervoltage)
	lastWord(t, conn, 0x14, 0x00, 0xD2, "disable undervoltage")
	_ = g.DisableInterrupt(Max17048SourceOvervoltage)
	lastWord(t, conn, 0x14, 0x00, 0xFF, "disable overvoltage")
}

func TestMax17048Reset(t *testing.T) {
	conn := newMockConnection()
	g, _ := NewMax17048Full(conn, 1)
	if err := g.Reset(); err != nil {
		t.Fatal(err)
	}
	lastWord(t, conn, 0xFE, 0x54, 0x00, "reset")
}
