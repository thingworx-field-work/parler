"""Synthetic turbo-generator operating manual (110 pages).

Fictional manufacturer "RK&T" (Reinholt, Kessler & Thal). All text is invented
for Parler document-retrieval fixtures and does not describe a real product.

Authoring rule for body pages (page 5 onwards): no line may start with a bare
integer followed by a word, because the converter reads such a line as a Part A
chapter heading. Write quantities as words or with a decimal point ("6.0 bar").
"""

LETTERHEAD = "Reinholt, Kessler & Thal AG"
DOC_CODE = "Order 7.318.042 - Operating Manual"
SOURCE_NAME = "7.318.042 Manual_RKT_OperatingManual.pdf"

CHAPTERS = [
    ("1", "General notes", "01-001"),
    ("2", "Safety", "02-001"),
    ("3", "Description of the turbo-generator set", "03-001"),
    ("4", "Transport and installation", "04-001"),
    ("5", "Commissioning", "05-001"),
    ("6", "Operation", "06-001"),
    ("7", "Maintenance", "07-001"),
    ("8", "Trouble, causes and their elimination", "08-001"),
    ("9", "Shutdown and conservation", "09-001"),
    ("10", "Spare parts", "10-001"),
]


def _page(heading: str, *paragraphs: str, chapter: str | None = None) -> list[tuple]:
    blocks: list[tuple] = []
    if chapter:
        blocks.append(("h", chapter))
    blocks.append(("h", heading))
    blocks.extend(("p", p) for p in paragraphs)
    return blocks


FRONT = [
    # 1
    [
        ("title", "Operating Manual"),
        ("lines", ["Turbo-generator set CB 24 GT4", "Back-pressure steam turbine with gearbox and generator",
                   "Order 7.318.042", "Edition 04.2019"]),
        ("p", "Read this manual before working on the turbo-generator set. Keep it at the machine."),
    ],
    # 2
    [
        ("h", "Part A Operating Instructions"),
        ("lines", [f"{num} {title} {code}" for num, title, code in CHAPTERS]),
        ("h", "Part B Drawings and diagrams"),
        ("lines", ["Arrangement drawing", "Oil system diagram", "Steam and drain diagram",
                   "Instrument list"]),
    ],
    # 3
    [
        ("h", "Scope of documentation"),
        ("p", "The documentation of the turbo-generator set consists of this operating manual (Part A), "
              "the drawings and diagrams (Part B) and the site installation requirements for the "
              "turbine."),
        ("p", "The coupling between gearbox and generator is described in the separate operating manual "
              "of the coupling manufacturer."),
    ],
    # 4
    [
        ("h", "Notes on this manual"),
        ("p", "Safety notes are marked with the signal words danger, warning and caution. Danger marks an "
              "imminent hazard that leads to death or serious injury; warning marks a possible hazard; "
              "caution marks a hazard to the machine."),
        ("p", "Values given in this manual apply to the machine as delivered. Values in the instrument "
              "list of Part B take precedence where they differ."),
    ],
]

CH1 = [
    # 5
    [
        ("h", "1 General notes"),
        ("h", "1.1 Preface"),
        ("p", "This operating manual describes the safe operation and maintenance of the turbo-generator "
              "set. This steam turbine was designed and built according to the engineering practice "
              "and the safety rules that applied at the time of delivery."),
        ("p", "Operating personnel must be trained on the machine and must have read this manual, "
              "especially the chapter on safety, before they operate the set."),
    ],
    _page("1.2 Intended use",
          "The turbo-generator set converts the energy of process steam into electrical energy. The "
          "exhaust steam is used in the process steam header of the plant.",
          "Any other use, operation outside the rated data, or modification without written approval "
          "of the manufacturer is not intended use."),
    _page("1.3 Rated data",
          "Live steam pressure, live steam temperature, back pressure, speed and electrical output are "
          "given on the rating plate and in the data sheet of Part B.",
          "The turbine runs at its rated speed and drives the generator through a parallel-shaft "
          "gearbox."),
    _page("1.4 Warranty and liability",
          "The warranty expires when the machine is operated outside the rated data, when non-original "
          "spare parts are used, or when the maintenance schedule is not followed.",
          "Keep the operating log up to date; it is required for warranty claims."),
    _page("1.5 Personnel qualification",
          "Operation, maintenance and repair are reserved for qualified personnel who are "
          "authorized by the plant owner.",
          "Work on the electrical equipment is reserved for qualified electricians."),
    _page("1.6 Documentation of changes",
          "Record every change of settings, every repair and every replacement of parts in the "
          "operating log together with the date and the name of the person responsible."),
    _page("1.7 Oil vapour extraction",
          "The oil tank is kept under a slight vacuum by the oil vapour extractor. The extracted air "
          "passes through the oil mist separator, which returns the separated oil to the tank.",
          "A blocked oil mist separator raises the pressure in the bearing housings and causes oil "
          "leakage at the shaft seals."),
    _page("1.8 Units and abbreviations",
          "Pressures are given as gauge pressure unless stated otherwise. Temperatures are given in "
          "degrees Celsius.",
          "Abbreviations used in this manual are listed in the instrument list of Part B."),
    _page("1.9 Environmental protection",
          "Used oil, filter elements and oil-soaked cleaning material must be disposed of according to "
          "local regulations. Do not let oil or cooling water with additives enter the drainage "
          "system."),
    _page("1.10 Contact",
          "For questions about operation and spare parts contact the service department of the "
          "manufacturer and quote the order number of the machine.",
          "Service: service@rkt-turbines.example"),
]

CH2 = [
    # 15
    _page("2.1 General safety notes",
          "Observe the safety notes in this chapter and in the individual chapters of this manual. "
          "Protective devices must never be removed or bridged while the set is in operation.",
          chapter="2 Safety"),
    _page("2.2 Hazards from steam and hot surfaces",
          "Danger: escaping steam causes severe burns. Do not open flanges, drains or instrument "
          "connections while the pipes are under pressure.",
          "Warning: casing and valves remain hot for many hours after shutdown."),
    _page("2.3 Hazards from rotating parts",
          "Warning: never reach into the coupling area while the shaft is turning. Fit the coupling "
          "guards before start-up."),
    _page("2.3.1 Noise",
          "The sound level near the running set exceeds the limit for unprotected hearing. Wear hearing "
          "protection in the turbine area."),
    _page("2.3.2 Fire protection",
          "Oil leaking onto hot surfaces can ignite. Repair leaks immediately and replace oil-soaked "
          "insulation. Keep fire extinguishers ready near the oil system."),
    _page("2.3.3 Lifting and transport",
          "Use only the lifting points shown in the arrangement drawing. Never stand under suspended "
          "loads."),
    # 21
    [
        ("h", "2.4 Notes on safety for operation"),
        ("p", "The turbine protection shuts the turbine down automatically by closing the quick-closing "
              "valve when one of the following limits is exceeded. Each trip is preceded by an alarm "
              "at a lower limit so that the operator can react in time."),
        ("bullets", [
            "Overspeed of the turbine rotor.",
            "Live steam pressure too high or too low.",
            "Live steam temperature too high.",
            "Bearing temperature too high.",
            "Lube oil pressure too low.",
            "Vibrations too high.",
            "Axial position of the rotor out of limits.",
        ]),
        ("p", "Warning: after a trip the turbine may be restarted only when the cause for the trouble has "
              "been found and eliminated. Record every trip and alarm in the operating log. Repeated "
              "restarts without eliminating the cause can damage the bearings and the blading."),
    ],
    _page("2.5 Testing the protective devices",
          "Test the overspeed trip and the quick-closing valve at the intervals of the maintenance "
          "schedule and after every repair of the governor. Record the trip speed in the operating "
          "log."),
    _page("2.6 Emergency stop",
          "The emergency stop buttons in the control room and at the machine close the quick-closing "
          "valve and open the generator breaker. Use the emergency stop only in case of danger."),
    _page("2.7 Personal protective equipment",
          "Wear safety shoes, hearing protection, gloves and eye protection when working at the "
          "machine. Wear heat-resistant gloves when working on hot parts."),
]

CH3 = [
    # 25
    _page("3.1 Overview",
          "The set consists of the back-pressure steam turbine, the gearbox, the generator, the oil "
          "system and the control system, mounted on a common base frame.",
          chapter="3 Description of the turbo-generator set"),
    _page("3.2 Turbine casing",
          "The casing is split horizontally. The upper half can be removed for inspection of the rotor "
          "and the blading without disconnecting the steam pipes."),
    _page("3.3 Turbine rotor",
          "The rotor carries a velocity-compounded control stage and several reaction stages. It runs "
          "in two tilting-pad journal bearings and one tilting-pad thrust bearing."),
    _page("3.4 Shaft seals",
          "Labyrinth seals at both shaft ends limit the leakage of steam to atmosphere. Leak-off steam "
          "is led to the gland steam condenser."),
    _page("3.5 Quick-closing valve and control valves",
          "The quick-closing valve shuts off the live steam in case of a trip. The control valves are "
          "moved by a hydraulic servomotor and set the steam flow according to the governor signal."),
    _page("3.6 Gearbox",
          "The parallel-shaft gearbox reduces the turbine speed to the generator speed. Its bearings are "
          "lubricated from the common oil system."),
    _page("3.7 Generator",
          "The generator is a brushless synchronous machine with air cooling through a water-cooled heat "
          "exchanger. Stator winding temperatures are monitored by resistance thermometers."),
    _page("3.8 Oil system",
          "The main oil pump is driven by the gearbox. An auxiliary oil pump supplies oil during "
          "start-up and shutdown, and an emergency oil pump supplies the bearings during coast-down "
          "after a power failure."),
    _page("3.9 Governor",
          "The electronic governor controls speed during start-up and back pressure or load during "
          "operation. It also contains the electronic overspeed protection."),
    _page("3.10 Monitoring",
          "Bearing temperatures, shaft vibration, lube oil pressure, axial position, speed and steam "
          "values are shown on the operator panel and passed to the plant control system."),
]

CH4 = [
    # 35
    _page("4.1 Transport",
          "The set is delivered on its base frame. Lift it only at the marked lifting points and keep "
          "it level during transport.",
          chapter="4 Transport and installation"),
    _page("4.2 Storage",
          "If the set is stored before installation, keep it dry and protected against dust. Turn the "
          "rotor by a quarter of a turn every month to protect the bearings."),
    _page("4.3 Installation",
          "Install the set according to the site installation requirements for the turbine. Check the foundation and the anchor bolts before setting the base frame."),
    _page("4.4 Alignment",
          "Check the alignment of turbine, gearbox and generator after setting the base frame and again "
          "after grouting and after connecting the pipes."),
    _page("4.5 Connecting the piping",
          "Connect the steam, drain, oil and cooling water pipes free of stress. Remove all transport "
          "covers and blank flanges before connecting."),
    _page("4.6 Filling the oil system",
          "Fill the oil tank with the specified oil through a fine filter. Flush the oil system until "
          "the filters remain clean."),
    _page("4.7 Electrical connection",
          "Connect the generator, the auxiliary drives and the control cabinet according to the circuit "
          "diagrams. Check the direction of rotation of the pump motors."),
    _page("4.8 Insulation",
          "Insulate the casing and the steam pipes after the pressure test. Keep the flange joints "
          "accessible."),
    _page("4.9 Preservation before commissioning",
          "If commissioning is delayed, keep the preservation of the turbine and the oil system in "
          "place and check it regularly."),
    _page("4.10 Handover for commissioning",
          "Hand over the set to the commissioning team when the installation checklist is completed and "
          "signed."),
]

CH5 = [
    # 45
    _page("5.1 Preconditions",
          "Before the first start the oil system must be flushed, the steam pipes blown out, the "
          "protective devices tested and the alignment recorded.",
          chapter="5 Commissioning"),
    _page("5.2 Checking the oil system",
          "Start the auxiliary oil pump and check oil pressure, oil temperature and the oil flow at "
          "every bearing. Check the oil system for leaks."),
    _page("5.3 Checking the protective devices",
          "Test the quick-closing valve, the overspeed trip and every protection signal before steam is "
          "admitted to the turbine."),
    _page("5.4 Warming up",
          "Open the drains and warm up the live steam pipe slowly. Admit steam to the turbine only when "
          "the pipe is drained and warm."),
    _page("5.5 First run-up",
          "Run the turbine up to low speed and listen for rubbing noise. Hold the speed and check "
          "bearing temperatures and vibration before increasing speed further."),
    _page("5.6 Overspeed test",
          "Raise the speed slowly until the overspeed trip closes the quick-closing valve. Record the "
          "trip speed and compare it with the setting in the instrument list."),
    _page("5.7 Synchronizing",
          "Synchronize the generator with the grid using the automatic synchronizer. Then take over "
          "load in small steps."),
    _page("5.8 Load operation",
          "Increase the load slowly while watching bearing temperatures, vibration and axial position. "
          "Switch the governor to back-pressure control when the process header can take the steam."),
    _page("5.9 Commissioning report",
          "Record all set values, test results and measured values in the commissioning report."),
    _page("5.10 Trial run",
          "The trial run lasts until the set has run at full load under stable conditions for the time "
          "agreed in the contract."),
]

CH6 = [
    # 55
    _page("6.1 Start-up from cold condition",
          "Start the auxiliary oil pump, open the drains and warm up the steam pipes. Run up the turbine "
          "following the start-up curve in Part B.",
          chapter="6 Operation"),
    _page("6.2 Start-up from warm condition",
          "After a short standstill the turbine can be started with a shorter warm-up time. Follow the "
          "start-up curve for warm condition."),
    _page("6.3 Monitoring during operation",
          "Check bearing temperatures, vibration, lube oil pressure, oil temperature and steam values on "
          "every shift and record them in the operating log."),
    _page("6.4 Load changes",
          "Change the load slowly. Large and fast load changes cause thermal stress in the casing and "
          "the rotor."),
    _page("6.5 Operation in island mode",
          "In island mode the governor controls speed and frequency. Observe the limits for load steps "
          "given in Part B."),
    _page("6.6 Normal shutdown",
          "Reduce the load, open the generator breaker and close the quick-closing valve. The auxiliary "
          "oil pump starts automatically when the speed falls."),
    _page("6.7 Turning after shutdown",
          "Keep the auxiliary oil pump running until the bearing temperatures have fallen to the value "
          "given in the instrument list."),
    _page("6.8 Operating log",
          "Record start-ups, shutdowns, alarms, trips and all abnormal observations in the operating "
          "log."),
    _page("6.9 Rounds",
          "On each round check the machine for leaks, unusual noise and unusual smell, and check the "
          "level in the oil tank."),
    _page("6.10 Operation with reduced back pressure",
          "Operation below the minimum back pressure is not permitted because it overloads the last "
          "stage blading."),
]

CH7 = [
    # 65
    _page("7.1 Maintenance schedule",
          "Carry out maintenance at the intervals of the maintenance schedule in Part B. Shorten the "
          "intervals under severe operating conditions.",
          chapter="7 Maintenance"),
    _page("7.2 Daily checks",
          "Check oil level, oil temperature, oil pressure, leaks and the readings of the monitoring "
          "system."),
    _page("7.3 Weekly checks",
          "Test the auxiliary oil pump and the emergency oil pump by a short test run. Check the oil "
          "filters and switch over to the clean filter when the differential pressure is high."),
    _page("7.4 Monthly checks",
          "Take an oil sample and have it analysed for water content, particles and ageing. Check the "
          "free movement of the quick-closing valve by a partial stroke test."),
    _page("7.5 Oil change",
          "Change the oil when the oil analysis shows that the limit values are exceeded. Clean the oil "
          "tank before refilling."),
    _page("7.6 Oil filters",
          "Replace the filter elements when the differential pressure alarm occurs. Vent the filter "
          "housing after replacing the element."),
    _page("7.7 Oil mist separator",
          "Check the oil mist separator on the oil tank vent for oil return and pressure drop. Replace "
          "the separator element when the tank pressure rises or oil escapes at the vent.",
          "A clogged oil mist separator causes oil leakage at the bearing housings. Check the drain line "
          "of the separator for free flow."),
    _page("7.8 Coolers",
          "Clean the oil cooler and the generator air cooler on the water side when the cooling "
          "performance falls."),
    _page("7.9 Steam strainer",
          "Inspect the steam strainer at every major inspection and remove deposits."),
    _page("7.10 Governor and servomotor",
          "Check the servomotor for leaks and free movement. Leaking oil seals at the servomotor must be "
          "replaced."),
    _page("7.11 Bearings",
          "Inspect the bearing pads at the major inspection. Measure the bearing clearance and replace "
          "pads with worn white metal."),
    _page("7.12 Blading",
          "Inspect the blading for deposits, erosion and damage when the casing is opened."),
    _page("7.13 Coupling",
          "Check the alignment and the coupling at the intervals given by the coupling manufacturer."),
    _page("7.14 Generator",
          "Check the insulation resistance of the stator winding and clean the air cooler as described "
          "in the generator documentation."),
    _page("7.15 Major inspection",
          "At the major inspection open the turbine casing, inspect rotor, blading, seals and bearings, "
          "and replace worn parts."),
]

CH8 = [
    # 80
    [
        ("h", "8 Trouble, causes and their elimination"),
        ("p", "This chapter tells you what to check first when the turbine trips, alarms or runs "
              "abnormally, and what must be done before restarting. The turbine protection trips the "
              "turbine on overspeed, high bearing temperature, low lube oil pressure, high vibration and "
              "wrong axial position. An unplanned shutdown always has a cause that must be found and "
              "eliminated before the turbo-generator set is brought back online."),
        ("p", "Before restarting after a trip or an alarm shutdown: read the alarm list, check bearing "
              "temperatures and vibration trends before the trip, check the lube oil pressure and the "
              "oil level, and inspect the machine for leaks and unusual noise. Recover the set only "
              "after the cause has been eliminated and recorded in the operating log."),
        ("table", [
            ["Trouble", "Cause", "Elimination"],
            ["Lube oil pressure too low or dropped", "Oil filter clogged; oil level too low; pump worn",
             "Switch filter; top up oil; check the pump before restarting"],
            ["Bearing temperature too high", "Oil too hot; oil flow too low; bearing damaged",
             "Clean the oil cooler; check oil flow; inspect the bearing pads"],
            ["Vibrations too high", "Misalignment; unbalance; bearing damage",
             "Check alignment and coupling; balance the rotor; inspect bearings"],
        ]),
    ],
    # 81
    [
        ("h", "8.1 Trouble table (continued)"),
        ("table", [
            ["Trouble", "Cause", "Elimination"],
            ["Turbine trips on overspeed", "Governor fault; control valve sticking",
             "Check the governor and the valve stems before restarting"],
            ["Leaking oil seal at the servomotor", "Seal worn", "Replace the seal"],
            ["Speed hunts", "Governor settings; air in the control oil",
             "Adjust the governor; vent the control oil system"],
            ["Leaking steam valve", "Valve seat damaged", "Grind in or replace the valve seat"],
            ["Water in the oil", "Leaking oil cooler; gland steam leakage",
             "Check the cooler; check the gland steam system; change the oil"],
            ["Unsmooth running", "Rubbing; deposits on the blading", "Inspect the rotor and the blading"],
        ]),
    ],
    # 82
    [
        ("h", "8.2 Restart after a trip"),
        ("p", "After the cause of the trip has been eliminated, reset the protection, start the auxiliary "
              "oil pump and restart the turbine following the start-up procedure for warm condition. "
              "Watch bearing temperature and vibration closely during run-up."),
        ("p", "If the turbine trips again for the same reason, do not restart it. Contact the service "
              "department of the manufacturer."),
    ],
]

CH9 = [
    # 83
    _page("9.1 Short standstill",
          "For a standstill of a few days keep the auxiliary oil pump running for some hours every day "
          "and keep the drains open.",
          chapter="9 Shutdown and conservation"),
    _page("9.2 Long standstill",
          "For a longer standstill dry the turbine with warm air and fill the oil system with "
          "preservation oil."),
    _page("9.3 Conservation of the steam path",
          "Keep the steam path dry. Close the quick-closing valve and the exhaust valve and open the "
          "casing drains."),
    _page("9.4 Conservation of the oil system",
          "Circulate the oil regularly and check the oil for water content during the standstill."),
    _page("9.5 Conservation of the generator",
          "Keep the generator winding dry by switching on the anti-condensation heater."),
    _page("9.6 Recommissioning after conservation",
          "Remove the preservation, check the oil and carry out the commissioning steps for the "
          "protective devices before restarting."),
    _page("9.7 Decommissioning",
          "When the set is finally taken out of service, drain the oil and the cooling water and dispose "
          "of them properly."),
]

CH10 = [
    # 90
    [
        ("h", "10 Spare parts"),
        ("p", "Keep a stock of spare parts for the wearing parts listed below. Order spare parts from the "
              "manufacturer and quote the order number and the part number."),
        ("table", [
            ["Part", "Recommended stock"],
            ["Journal bearing pads, turbine", "one set"],
            ["Thrust bearing pads", "one set"],
            ["Labyrinth seal strips", "one set"],
            ["Oil filter elements", "four pieces"],
            ["Servomotor seal kit", "one set"],
        ]),
    ],
    _page("10.1 Ordering spare parts",
          "Quote the order number, the part number from the spare parts list and the required "
          "quantity."),
    _page("10.2 Storage of spare parts",
          "Store spare parts dry and in their original packing. Protect bearing pads against corrosion."),
    _page("10.3 Spare parts for the oil system",
          "Keep filter elements, pump seals and the oil mist separator element in stock."),
    _page("10.4 Spare parts for the governor",
          "Keep a servomotor seal kit and a spare speed sensor in stock."),
    _page("10.5 Spare parts for the generator",
          "Spare parts for the generator are listed in the generator documentation."),
]

PART_B_TITLES = [
    "Arrangement drawing, plan view", "Arrangement drawing, side view", "Foundation load plan",
    "Oil system diagram", "Oil tank and oil mist separator", "Steam and drain diagram",
    "Gland steam diagram", "Cooling water diagram", "Instrument list, turbine",
    "Instrument list, gearbox and generator", "Start-up curve, cold condition",
    "Start-up curve, warm condition", "Maintenance schedule", "Lubrication schedule",
    "Rating plate data",
]


def _part_b() -> list[list[tuple]]:
    pages = []
    for i, title in enumerate(PART_B_TITLES):
        blocks: list[tuple] = []
        if i == 0:
            blocks.append(("h", "Part B Drawings and diagrams"))
        blocks.append(("h", title))
        blocks.append(("p", f"Drawing sheet: {title.lower()}. The drawing shows the as-delivered state "
                            f"of the turbo-generator set for order 7.318.042."))
        pages.append(blocks)
    return pages


PAGES = FRONT + CH1 + CH2 + CH3 + CH4 + CH5 + CH6 + CH7 + CH8 + CH9 + CH10 + _part_b()

CHAPTER_START_PAGES = {"01": 5, "02": 15, "03": 25, "04": 35, "05": 45, "06": 55,
                       "07": 65, "08": 80, "09": 83, "10": 90}
