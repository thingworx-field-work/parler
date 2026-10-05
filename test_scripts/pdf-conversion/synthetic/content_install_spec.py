"""Synthetic site installation requirements for a steam turbine (21 pages).

Fictional manufacturer "RK&T" (Reinholt, Kessler & Thal). All text is invented
for Parler document-retrieval fixtures and does not describe a real product.
"""

LETTERHEAD = "Reinholt, Kessler & Thal AG"
DOC_CODE = "Order 7.318.042 - Installation"
SOURCE_NAME = "7.318.042 Manual_RKT_Install_Spec.pdf"

TOC = [
    ("1 GENERAL", 3),
    ("2 SPACE REQUIREMENTS", 4),
    ("3 FOUNDATION DESIGN", 6),
    ("4 ALIGNMENT OF TURBINE AND DRIVEN MACHINE", 6),
    ("5 PIPING", 7),
    ("5.1 LIVE STEAM PIPE", 7),
    ("5.2 EXHAUST STEAM PIPE", 8),
    ("5.3 DRAIN PIPES", 9),
    ("5.4 GLAND STEAM PIPE", 10),
    ("5.5 COOLING WATER", 11),
    ("5.6 BLOW-OFF LINE AND START-UP AGAINST BACK PRESSURE", 12),
    ("6 OIL SYSTEM", 13),
    ("7 ELECTRICAL INSTALLATION", 15),
    ("8 INSULATION", 17),
    ("9 CONTROL AND MONITORING", 18),
    ("10 CHECKLIST BEFORE COMMISSIONING", 20),
]

PAGES = [
    # 1
    [
        ("title", "Site Installation Requirements"),
        ("lines", ["for Steam Turbines",
                   "Turbo-generator set CB 24 GT4", "Order 7.318.042"]),
        ("p", "This specification describes the requirements that the plant owner must meet in the area "
              "surrounding the turbine: foundation, alignment, piping, oil system, electrical "
              "installation, insulation and monitoring."),
    ],
    # 2
    [
        ("h", "TABLE OF CONTENTS"),
        ("toc", TOC),
    ],
    # 3
    [
        ("h", "1 General"),
        ("p", "The turbine supplier delivers the turbine, the gearbox, the generator and the oil system "
              "on a common base frame. The plant owner provides the foundation, the connecting piping, "
              "the cooling water supply and the electrical connections."),
        ("p", "All work must follow the drawings listed in the order documentation. Deviations require "
              "written approval by the turbine supplier."),
        ("p", "Warning: the installation area must be kept free of flammable material. Hot surfaces of "
              "the turbine casing and steam pipes reach more than 400 degrees Celsius during operation."),
    ],
    # 4
    [
        ("h", "2 Space requirements"),
        ("p", "Keep a free area of at least 1.5 m around the base frame for operation and maintenance. "
              "Above the turbine, provide a crane hook height that allows the upper casing half and "
              "the rotor to be lifted out."),
        ("table", [
            ["Component", "Lifting weight (t)", "Clear height above floor (m)"],
            ["Upper casing half", "4.2", "5.5"],
            ["Turbine rotor", "2.8", "5.0"],
            ["Generator rotor", "6.5", "6.0"],
        ]),
    ],
    # 5
    [
        ("h", "2.1 Access and ventilation"),
        ("p", "Provide a transport route for the heaviest single part and a laydown area next to the "
              "turbine during inspections. The turbine hall must be ventilated so that the ambient "
              "temperature near the oil system stays below 45 degrees Celsius."),
        ("p", "Floor openings for piping and cables must be closed with covers that can carry the "
              "loads of maintenance work."),
    ],
    # 6
    [
        ("h", "3 Foundation design"),
        ("p", "The foundation must carry the static weight of the turbo-generator set and the dynamic "
              "loads during operation, including short-circuit torque of the generator. Design the "
              "foundation as a reinforced concrete block or a table foundation on elastic supports."),
        ("p", "The natural frequencies of the foundation must stay at least 20 percent away from the "
              "running speeds of the turbine and the generator. Grout the base frame with non-shrinking "
              "grout after final alignment."),
        ("h", "4 Alignment of turbine and driven machine"),
        ("p", "The turbine, the gearbox and the generator are pre-aligned on the base frame at the "
              "factory. After the base frame is set on the foundation, check the alignment of the "
              "turbine to the driven machine at every coupling and correct it with shims."),
        ("p", "Record the alignment values in the installation report. Check the alignment again after "
              "grouting and after the connecting pipes are fitted, because pipe forces can change it."),
    ],
    # 7
    [
        ("h", "5 Piping"),
        ("p", "Connect all pipes free of stress. Pipe forces and moments at the turbine flanges must "
              "not exceed the values in the piping drawing."),
        ("h", "5.1 Live steam pipe"),
        ("p", "Install a steam strainer and a quick-closing valve upstream of the turbine. Blow out the "
              "live steam pipe before it is connected to the turbine until no particles are found on "
              "the target plates."),
    ],
    # 8
    [
        ("h", "5.2 Exhaust steam pipe"),
        ("p", "The exhaust steam pipe leads to the process steam header. Provide an expansion joint "
              "and fixed points so that thermal growth does not load the turbine casing."),
        ("p", "Install a safety valve in the exhaust steam line that protects the turbine casing "
              "against excessive back pressure."),
    ],
    # 9
    [
        ("h", "5.3 Drain pipes"),
        ("p", "Casing drains and valve drains must slope continuously to the drain collector. Do not "
              "combine drains of different pressure levels in one pipe without check valves."),
        ("p", "Open all drains before start-up so that condensate cannot reach the turbine blading."),
    ],
    # 10
    [
        ("h", "5.4 Gland steam pipe"),
        ("p", "Leak-off steam from the shaft glands is led to a gland steam condenser or to atmosphere at "
              "a safe location. The pipe must not rise after leaving the turbine."),
    ],
    # 11
    [
        ("h", "5.5 Cooling water"),
        ("p", "The oil cooler and the generator air cooler need cooling water at the flow rate and "
              "temperature given in the data sheet. Provide isolating valves and thermometers at the "
              "inlet and outlet of each cooler."),
        ("table", [
            ["Consumer", "Flow (m3/h)", "Inlet temperature max. (C)"],
            ["Oil cooler", "12", "32"],
            ["Generator air cooler", "38", "32"],
        ]),
    ],
    # 12
    [
        ("h", "5.6 Blow-off line and start-up against back pressure"),
        ("p", "During start-up the exhaust steam is blown off to atmosphere until the turbine is "
              "synchronized and the process header can take the steam. The required arrangement depends "
              "on the back pressure of the process header."),
        ("h", "Back pressure lower than 6 bar (g)"),
        ("p", "Start up against the blow-off valve with the header valve closed. Open the header valve "
              "slowly after synchronization and close the blow-off valve at the same rate."),
        ("h", "Back pressure higher than 6 bar (g) and lower than 11 bar (g)"),
        ("p", "Warm up the exhaust pipe through the bypass before start-up. Hold the back pressure with "
              "the blow-off control valve until the header pressure is reached."),
        ("h", "Back pressure higher than 11 bar (g)"),
        ("p", "Start up only with the back-pressure controller in automatic mode and with the bypass "
              "open. Increase load in steps and watch the axial position of the rotor."),
    ],
    # 13
    [
        ("h", "6 Oil system"),
        ("p", "The oil system supplies lube oil to the bearings of turbine, gearbox and generator and "
              "control oil to the governor. Fill the system only with the oil grade given in the "
              "lubrication schedule."),
        ("p", "Flush the oil system before commissioning with the bearings bypassed until the filter "
              "stays clean for four hours."),
    ],
    # 14
    [
        ("h", "6.1 Oil tank and oil mist"),
        ("p", "Vent the oil tank to a safe area. The oil mist separator on the tank vent must be "
              "connected with a rising pipe and must not be blocked."),
        ("p", "Maintenance of the oil mist separator is described in the operating manual."),
    ],
    # 15
    [
        ("h", "7 Electrical installation"),
        ("p", "Connect the generator, the auxiliary motors and the control cabinet according to the "
              "circuit diagrams. Earth the base frame at two opposite corners."),
        ("p", "Run signal cables separately from power cables and use shielded cables for speed and "
              "vibration sensors."),
    ],
    # 16
    [
        ("h", "7.1 Emergency power"),
        ("p", "The emergency oil pump must be supplied from a battery or an emergency power supply so "
              "that the bearings are lubricated during coast-down after a power failure."),
    ],
    # 17
    [
        ("h", "8 Insulation"),
        ("p", "Insulate the turbine casing, the valves and the steam pipes after the pressure test. "
              "Leave the flanges of the casing joint and the instrument connections accessible."),
        ("p", "Insulation must be protected against oil. Oil-soaked insulation is a fire hazard and must "
              "be replaced."),
    ],
    # 18
    [
        ("h", "9 Control and monitoring"),
        ("p", "The plant control system receives the turbine signals for speed, bearing temperatures, "
              "vibration, lube oil pressure and axial position. Alarm and trip values are listed in the "
              "instrument list."),
    ],
    # 19
    [
        ("h", "9.1 Signal exchange"),
        ("p", "Hard-wired signals are required for the trip commands between the turbine protection and "
              "the generator protection. Status and measured values may be exchanged over a fieldbus."),
    ],
    # 20
    [
        ("h", "10 Checklist before commissioning"),
        ("bullets", [
            "Foundation grouted and cured.",
            "Alignment checked after grouting and after piping.",
            "Live steam pipe blown out and strainer fitted.",
            "Oil system flushed and filled.",
            "Cooling water available at the required flow.",
            "Electrical connections tested.",
        ]),
    ],
    # 21
    [
        ("h", "10.1 Handover"),
        ("p", "The installation is handed over to the commissioning team when every item of the "
              "checklist is signed by the plant owner and the supplier."),
    ],
]
