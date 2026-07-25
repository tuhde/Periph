#include <Wire.h>
#include "I2CTransport.h"
#include "MCP23017.h"

I2CTransport transport(Wire, 0x20);
MCP23017Full mcp(transport);

void setup() {
    Serial.begin(115200);
    Wire.begin();

    mcp.configure_pullup(1, 0x7F);                          // Enable pull-ups on GPB0–GPB6, (port=1, mask=0x7F) → None
    Serial.println("=== Knight Rider scanner with button override ===");
    Serial.println("  pos  btn_msk  out_mask");
}

unsigned long last_print = 0;

void loop() {
    static int step = 0;

    uint8_t port_b = mcp.read_port(1);                      // Read GPB0–GPB6 buttons, (port=1) → uint8_t 0–127
    uint8_t btn_mask = (~port_b) & 0x7F;                     // Invert: active-low buttons → active-high mask

    int direction = (step / 7) % 2 == 0 ? 1 : -1;
    int pos = step % 7;
    if (direction == -1) pos = 6 - pos;

    uint8_t scanner = 1 << pos;
    uint8_t output = btn_mask | scanner;                    // Button takes priority over scanner

    mcp.write_port(0, output);                             // Write PORTA outputs, (port=0, mask) → None

    if (millis() - last_print >= 100) {
        last_print = millis();
        Serial.print("  ");
        Serial.print(pos);
        Serial.print("     ");
        Serial.print(btn_mask, HEX);
        Serial.print("        ");
        Serial.println(output, HEX);
        step++;
    }

    if (step >= 40) {
        mcp.write_port(0, 0x00);                             // Clear all outputs, (port=0, mask=0x00) → None
        Serial.println("=== Done ===");
        while (1) delay(1000);
    }
}