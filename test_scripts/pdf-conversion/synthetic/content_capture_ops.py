"""Synthetic CO2 capture unit operations manual (28 pages, hand-curated chunks).

Fictional manufacturer "Fernwick Labs", product "CarbaQ". All text is invented
for Parler document-retrieval fixtures and does not describe a real product.
"""

DOC_ID = "fernwick-carbaq-ops-v2"
TITLE = "Fernwick Labs CarbaQ CO2 Capture Solution Operations Manual"
ASSET_MODEL = "CarbaQ CO2 Capture Solution"
LETTERHEAD = "Fernwick Labs"
DOC_CODE = "CarbaQ Operations Manual V2.0"
SOURCE_NAME = "Fernwick Labs - CarbaQ - V.2.0.pdf"
DOCUMENT_VERSION = "2.0"
CONVERTED_AT = "2026-06-15T00:00:00Z"

# Troubleshooting table rows. The PDF table on pages 25-26 and the curated
# troubleshooting chunks are both generated from this list, so every statement
# a chunk makes is printed on the page it cites.
TROUBLE = [
    {"id": "troubleshooting-moisture-in-sight-glass", "page": 25,
     "problem": "Moisture in sight glass", "cause": "Condensate carried over from the chiller",
     "remedy": "Drain the chiller separator and check the auto-drain valve.",
     "heading": "Moisture in sight glass", "tags": ["moisture", "sight glass", "chiller"], "signals": []},
    {"id": "troubleshooting-uneven-gas-flow", "page": 25,
     "problem": "Uneven gas flow; gas enters the unit in surges",
     "cause": "Foam trap overfilled or a leak on the inlet hose",
     "remedy": "Empty the foam trap and tighten the inlet hose connections.",
     "heading": "Uneven gas flow into the unit", "tags": ["foam trap", "leak", "gas flow"], "signals": []},
    {"id": "troubleshooting-chiller-high-pressure-moisture", "page": 25,
     "problem": "Chiller High Pressure Shutdown with moisture in the carbon bed",
     "cause": "Moisture reached the activated carbon bed",
     "remedy": "Depressurize the unit, then dry or replace the carbon bed before restarting.",
     "heading": "Chiller High Pressure Shutdown - moisture in the carbon bed",
     "tags": ["chiller", "high pressure", "shutdown", "moisture", "carbon bed"],
     "signals": [{"kind": "alarm", "name": "Chiller High Pressure Shutdown"}]},
    {"id": "troubleshooting-receiver-high-pressure-shutdown", "page": 25,
     "problem": "Receiver High Pressure Shutdown", "cause": "Back pressure regulator set too high",
     "remedy": "Lower the BPR setting and vent the receiver slowly.",
     "heading": "Receiver High Pressure Shutdown", "tags": ["receiver", "high pressure", "shutdown", "bpr"],
     "signals": [{"kind": "alarm", "name": "Receiver High Pressure Shutdown"},
                 {"kind": "property", "name": "Receiver Pressure"}]},
    {"id": "troubleshooting-chiller-high-pressure-bpr", "page": 25,
     "problem": "Chiller High Pressure Shutdown", "cause": "Back pressure regulator was moved",
     "remedy": "Adjust the BPR about 1/8 of a turn at a time until Receiver Pressure reads 140 psi, "
               "then lock the adjusting screw. Allow up to 15 minutes for the pressure to settle "
               "after each step.",
     "heading": "Chiller High Pressure Shutdown - back pressure regulator",
     "tags": ["chiller", "receiver pressure", "high pressure", "shutdown", "bpr"],
     "signals": [{"kind": "alarm", "name": "Chiller High Pressure Shutdown"},
                 {"kind": "property", "name": "Receiver Pressure"}]},
    {"id": "troubleshooting-compressor-high-pressure-shutdown", "page": 25,
     "problem": "Compressor High Pressure Shutdown", "cause": "Blockage downstream of the compressor",
     "remedy": "Check the auto-drain and the check valve for blockage.",
     "heading": "Compressor High Pressure Shutdown",
     "tags": ["compressor", "high pressure", "shutdown", "blockage", "auto-drain"],
     "signals": [{"kind": "alarm", "name": "Compressor High Pressure Shutdown"}]},
    {"id": "troubleshooting-heater-high-temperature-shutdown", "page": 25,
     "problem": "Heater High Temperature Shutdown", "cause": "Heater thermostat fault or no gas flow",
     "remedy": "Check gas flow and the heater thermostat.",
     "heading": "Heater High Temperature Shutdown", "tags": ["heater", "high temperature", "shutdown"],
     "signals": [{"kind": "alarm", "name": "Heater High Temperature Shutdown"}]},
    {"id": "troubleshooting-negative-temperature-no-liquid", "page": 26,
     "problem": "Negative temperature but no liquid in the receiver",
     "cause": "Receiver insulation damaged or vent open",
     "remedy": "Check the receiver insulation and close the vent valve.",
     "heading": "Negative temperature but no liquid in the receiver",
     "tags": ["negative temperature", "no liquid", "receiver", "insulation"], "signals": []},
    {"id": "troubleshooting-over-pressurization-compressor", "page": 26,
     "problem": "Over pressurization of the compressor",
     "cause": "Coalescing filter blocked or outlet valve closed",
     "remedy": "Replace the coalescing filter; open the outlet valve.",
     "heading": "Over pressurization of the compressor",
     "tags": ["compressor", "over pressurization", "coalescing filter", "outlet valve"],
     "signals": [{"kind": "alarm", "name": "Compressor Over Pressurization"}]},
    {"id": "troubleshooting-compressor-false-operation", "page": 26,
     "problem": "Compressor false operation", "cause": "Contactor, breaker or VFD fault",
     "remedy": "Reset the breaker, check the contactor and the VFD fault log on the HMI.",
     "heading": "Compressor false operation",
     "tags": ["compressor", "false operation", "hmi", "breaker", "contactor", "vfd"],
     "signals": [{"kind": "alarm", "name": "Compressor False Operation"}]},
]

TROUBLE_ROWS_P25 = [[r["problem"], r["cause"], r["remedy"]] for r in TROUBLE if r["page"] == 25]
TROUBLE_ROWS_P26 = [[r["problem"], r["cause"], r["remedy"]] for r in TROUBLE if r["page"] == 26]


def _p(heading: str, *paragraphs: str) -> list[tuple]:
    return [("h", heading)] + [("p", p) for p in paragraphs]


PAGES = [
    # 1
    [
        ("title", "CarbaQ CO2 Capture Solution"),
        ("lines", ["Operations Manual", "Version 2.0", "Fernwick Labs"]),
        ("p", "This manual describes installation, operation, maintenance and troubleshooting of the "
              "CarbaQ unit, which recovers CO2 from fermentation off-gas and stores it as a liquid in a "
              "receiver vessel."),
    ],
    # 2
    [
        ("h", "Contents"),
        ("lines", ["1. Overview of the CarbaQ CO2 Capture Unit", "2. Installation",
                   "3. Operating the Unit", "4. Daily Checks", "5. CarbaQ Software Components",
                   "6. Maintenance", "7. Cleaning and Storage", "8. Gas Quality Checks",
                   "9. Troubleshooting Tips", "10. Service and Support"]),
    ],
    # 3
    _p("1. Overview of the CarbaQ CO2 Capture Unit",
       "Fermentation off-gas enters the unit through the foam trap, passes the activated carbon bed that "
       "removes odours and volatile organic compounds, and is compressed, cooled in the chiller and "
       "condensed into the receiver vessel.",
       "An oxygen sensor monitors the purity of the gas before it is condensed."),
    # 4
    _p("1.1 Process flow",
       "Foam trap, activated carbon bed, compressor, chiller, receiver vessel. The heater keeps the gas "
       "above its dew point before the carbon bed."),
    # 5
    _p("1.2 Main components",
       "The skid carries the compressor, the chiller, the carbon bed, the heater and the control cabinet "
       "with the HMI. The receiver vessel stands next to the skid."),
    # 6
    _p("1.3 Safety",
       "CO2 displaces oxygen. Operate the unit only in a ventilated area with a CO2 monitor. The receiver "
       "vessel is a pressure vessel; never exceed its rated pressure."),
    # 7
    _p("1.4 Technical data",
       "Capture rate, electrical supply and receiver capacity are given on the data plate of the unit."),
    # 8
    _p("2. Installation",
       "Place the skid on a level floor near the fermentation vessels. Keep the inlet hose short and "
       "free of low points where foam can collect."),
    # 9
    _p("2.1 Electrical connection",
       "Connect the unit to the supply given on the data plate. The unit must be earthed."),
    # 10
    _p("2.2 Preparing the receiver vessel",
       "Attach the receiver vessel to the liquid outlet of the chiller and open the receiver vent to "
       "purge air from the line."),
    # 11
    _p("2.3 Setting the back pressure regulator",
       "The back pressure regulator (BPR) keeps the receiver at the pressure needed for liquefaction. "
       "Set the BPR so that Receiver Pressure reads 140 psi with the unit running.",
       "Turn the BPR adjusting screw in steps of about 1/8 of a turn and wait for the pressure to "
       "settle after each step. Lock the adjusting screw when the value is reached."),
    # 12
    _p("2.4 Leak check",
       "Check all connections with leak detection spray before the first start."),
    # 13
    _p("3. Operating the Unit",
       "Start the unit from the HMI. The compressor starts when the oxygen content of the gas is below "
       "the set limit. Stop the unit from the HMI at the end of the fermentation."),
    # 14
    _p("4. Daily Checks",
       "Check the foam trap, the sight glass, the receiver pressure and the HMI alarm list every day."),
    # 15
    _p("5. CarbaQ Software Components",
       "The HMI shows the process screen, the trend screen and the alarm screen. The alarm screen lists "
       "active alarms with their time stamp."),
    # 16
    _p("5.1 HMI Alarms",
       "The unit raises alarms for high pressure, high temperature, low oxygen purity and compressor "
       "faults. Shutdown alarms stop the compressor and must be acknowledged before restart."),
    # 17
    _p("6. Maintenance",
       "Follow the maintenance intervals below. Record every maintenance action in the service log."),
    # 18
    _p("6.1 Replacing Activated Carbon",
       "Replace the activated carbon when the VOC alarm occurs or after the interval on the data plate. "
       "Stop the unit and depressurize the carbon bed before opening it."),
    # 19
    _p("6.2 Filters and drains",
       "Replace the coalescing filter element and check the auto-drain valve every three months."),
    # 20
    _p("6.3 Calibration of Oxygen Sensor",
       "Calibrate the oxygen sensor monthly with ambient air as span gas. Moisture on the sensor causes "
       "drift; dry the sample line before calibration.",
       "The O2 Percentage value on the HMI must read the span value within tolerance after "
       "calibration."),
    # 21
    _p("7. Cleaning and Storage",
       "Clean the foam trap and the inlet hose after every fermentation cycle."),
    # 22
    _p("7.1 Storage",
       "For storage, depressurize the unit, drain the chiller and close all valves."),
    # 23
    _p("7.2 Seasonal shutdown",
       "Before a seasonal shutdown replace the activated carbon and the filter elements."),
    # 24
    _p("8. Gas Quality Checks",
       "Take a gas sample from the receiver and analyse oxygen, VOC and moisture. A foam trap that is "
       "not emptied regularly raises the VOC content."),
    # 25
    [
        ("h", "9. Troubleshooting Tips"),
        ("table", [["Problem", "Possible cause", "Remedy"]] + TROUBLE_ROWS_P25),
    ],
    # 26
    [
        ("h", "9. Troubleshooting Tips (continued)"),
        ("table", [["Problem", "Possible cause", "Remedy"]] + TROUBLE_ROWS_P26),
    ],
    # 27
    _p("10. Service and Support",
       "For an urgent problem call the service hotline. For all other questions write to "
       "service@fernwick-labs.example and quote the serial number of the unit."),
    # 28
    _p("Revision History",
       "Version 2.0: troubleshooting table extended; BPR procedure updated."),
]


def _page_text(page: int) -> list[str]:
    """Paragraph and list text of one authored page, without its headings."""
    out: list[str] = []
    for block in PAGES[page - 1]:
        if block[0] == "p":
            out.append(block[1])
        elif block[0] in ("bullets", "lines"):
            out.extend(block[1])
    return out


def _section(chunk_id, content_type, heading, section_path, page_start, page_end, tags, signals, summary):
    """Section / maintenance chunk whose body is the text of the pages it cites."""
    body = "\n\n".join(p for page in range(page_start, page_end + 1) for p in _page_text(page))
    return {
        "chunkId": chunk_id, "contentType": content_type, "heading": heading,
        "sectionPath": section_path, "pageStart": page_start, "pageEnd": page_end,
        "tags": tags, "signals": signals, "summary": summary,
        "markdown": f"## {heading}\n\n{body}\n",
    }


def _trouble(row: dict) -> dict:
    return {
        "chunkId": row["id"], "contentType": "troubleshooting", "heading": row["heading"],
        "sectionPath": ["9. Troubleshooting Tips"], "pageStart": row["page"], "pageEnd": row["page"],
        "tags": row["tags"], "signals": row["signals"], "summary": f"{row['problem']}.",
        "markdown": (f"## {row['heading']}\n\nProblem: {row['problem']}\n\nCause: {row['cause']}\n\n"
                     f"Remedy: {row['remedy']}\n"),
    }


CURATED = [
    _section("overview-system-description", "section", "Overview of the CarbaQ CO2 Capture Unit",
             ["1. Overview of the CarbaQ CO2 Capture Unit"], 3, 7,
             ["overview", "system", "oxygen sensor", "activated carbon", "chiller", "receiver"], [],
             "Process flow and main components of the CarbaQ unit."),
    _section("installation-receiver-bpr", "section",
             "Attaching the Receiver and Setting the Back Pressure Regulator",
             ["2. Installation", "Preparing the Receiver Vessel"], 8, 12,
             ["installation", "receiver", "bpr", "back pressure regulator", "140 psi"],
             [{"kind": "property", "name": "Receiver Pressure"}],
             "Receiver connection and BPR setting to 140 psi."),
    _section("software-hmi-alarms", "section", "HMI Alarms and Software Screens",
             ["5. CarbaQ Software Components"], 15, 16,
             ["hmi", "alarm", "pressure", "temperature", "oxygen", "maintenance"],
             [{"kind": "alarm", "name": "HMI Alarms"}], "HMI screens and alarm handling."),
    _section("maintenance-activated-carbon", "maintenance", "Replacing Activated Carbon",
             ["6. Maintenance", "Replacing Activated Carbon"], 17, 18,
             ["maintenance", "activated carbon", "voc", "alarm", "shutdown"],
             [{"kind": "maintenance", "name": "Activated Carbon"}],
             "When and how to replace the activated carbon."),
    _section("maintenance-oxygen-sensor-calibration", "maintenance", "Calibration of Oxygen Sensor",
             ["6. Maintenance", "Calibration of Oxygen Sensor"], 20, 20,
             ["maintenance", "oxygen sensor", "calibration", "span", "moisture"],
             [{"kind": "property", "name": "O2 Percentage"}],
             "Monthly oxygen sensor calibration with ambient air."),
    _section("gas-quality-voc-sampling", "section", "Gas Quality Checks",
             ["8. Gas Quality Checks"], 24, 24, ["quality", "voc", "oxygen", "receiver", "foam trap"], [],
             "Sampling recovered gas for oxygen, VOC and moisture."),
    *[_trouble(r) for r in TROUBLE],
    _section("service-and-support-contact", "section", "Service and Support",
             ["10. Service and Support"], 27, 27, ["customer service", "contact", "urgent problem"], [],
             "How to reach customer service."),
]
