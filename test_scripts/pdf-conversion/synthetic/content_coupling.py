"""Synthetic coupling operating manual (13 pages).

Fictional manufacturer "KBM" (Kupplungsbau Mehlberg). All text is invented for
Parler document-retrieval fixtures and does not describe a real product.
"""

LETTERHEAD = "Kupplungsbau Mehlberg GmbH"
DOC_CODE = "KBN 31016 g"
SOURCE_NAME = "7.318.042 Manual_KBM.pdf"

TOC = [
    ("1. GENERAL INFORMATION", 3),
    ("2. FEATURES", 4),
    ("3. FUNCTION", 6),
    ("4. ASSEMBLY", 7),
    ("4.1 MOUNTING THE COUPLING HALVES", 7),
    ("4.2 ALIGNING", 8),
    ("4.2.1 ALIGNING BY MEANS OF A STRAIGHT EDGE AND FEELER GAUGE", 8),
    ("4.2.2 ALIGNING BY MEANS OF A DIAL GAUGE", 9),
    ("5. COMMISSIONING", 10),
    ("6. MAINTENANCE", 11),
    ("7. DISMANTLING", 12),
    ("8. DECLARATION OF THE MANUFACTURER", 13),
]

PAGES = [
    # 1
    [
        ("title", "Flexible pin type coupling"),
        ("lines", ["series KBN 21011", "Operating manual KBN 31016, Edition g",
                   "Model series FLEXO-N"]),
        ("table", [
            ["Role", "Name", "Date", "Signature"],
            ["Author", "Dipl.-Ing. M. Albrecht", "12.03.2019", "sgd. M. Albrecht"],
            ["Approved", "Dr.-Ing. S. Voigt", "18.03.2019", "sgd. S. Voigt"],
        ]),
        ("p", "This manual applies to FLEXO-N flexible pin type couplings supplied as part of order "
              "7.318.042. Keep it available at the installation site for the whole service life of the "
              "coupling."),
    ],
    # 2
    [
        ("h", "TABLE OF CONTENTS"),
        ("toc", TOC),
    ],
    # 3
    [
        ("h", "1. General information"),
        ("p", "The FLEXO-N coupling connects two shafts and transmits torque through steel pins fitted "
              "with elastic buffers. The buffers damp torsional shocks and compensate small shaft "
              "misalignment between the driving and the driven machine."),
        ("p", "Warning: carry out work on the coupling only while the drive train is stopped and "
              "locked out against unintended start-up. Observe the plant safety "
              "instructions and the documentation of the connected machines."),
        ("p", "Caution: use only original buffers and pins. Substitute parts change the torsional "
              "stiffness of the coupling and can cause damage to the connected machines."),
        ("p", "The manufacturer accepts no liability for damage caused by improper assembly, operation "
              "outside the rated data, or modifications that were not approved in writing."),
    ],
    # 4
    [
        ("h", "2. Features"),
        ("p", "The coupling consists of a pin half and a buffer half. The pin half carries hardened "
              "steel pins; the buffer half carries bores that receive the pins with their elastic "
              "buffers. Both halves are fitted to the shaft ends with a cylindrical or tapered bore "
              "and a feather key."),
        ("bullets", [
            "Fail-safe design: if the buffers wear out, the pins still transmit torque.",
            "Buffers can be replaced without moving the connected machines.",
            "Suitable for both directions of rotation.",
            "Permissible operating temperature of the buffers from minus 30 to plus 80 degrees Celsius.",
        ]),
        ("table", [
            ["Size", "Rated torque (Nm)", "Max. speed (1/min)", "Bore max. (mm)"],
            ["125", "900", "4600", "55"],
            ["160", "2100", "3600", "70"],
            ["200", "4200", "2900", "90"],
            ["250", "8500", "2300", "110"],
        ]),
    ],
    # 5
    [
        ("h", "2.1 Rated data and selection"),
        ("p", "The coupling size is selected from the rated torque of the driven machine, multiplied "
              "by the service factor of the application. Short-term peak torque during start-up may "
              "reach three times the rated torque."),
        ("p", "The supplied size for order 7.318.042 is size 200 with a service factor of 1.75 for a "
              "steam turbine driving a generator through a gearbox."),
        ("p", "Balancing: coupling halves are balanced individually. Mark the relative position of the "
              "halves before dismantling so that they can be refitted in the same position."),
    ],
    # 6
    [
        ("h", "3. Function"),
        ("p", "Torque is transmitted from the pin half to the buffer half through the elastic buffers. "
              "Under load the buffers deform slightly, which damps torque peaks and allows small radial, "
              "angular and axial misalignment."),
        ("p", "Before commissioning, inspect the coupling halves, pins and buffers for damage caused by "
              "transport or storage. Cracked pins, torn buffers or deformed bores are not acceptable."),
        ("p", "If the coupling shows any damage before commissioning, it may not be put into operation. "
              "Replace the damaged parts first and record the inspection result in the commissioning "
              "report."),
        ("p", "A damaged coupling that is put into operation can fail suddenly and endanger persons and "
              "the connected machines."),
    ],
    # 7
    [
        ("h", "4. Assembly"),
        ("h", "4.1 Mounting the coupling halves"),
        ("p", "Clean the shaft ends and the bores. Heat the coupling halves evenly to about 100 degrees "
              "Celsius and push them onto the shafts until they are flush with the shaft ends. Do not "
              "hammer the halves onto the shafts."),
        ("p", "Secure the halves axially with the set screws. Tighten the set screws to the torque given "
              "in the assembly drawing."),
        ("p", "Move the machines together until the distance between the halves matches the value "
              "given in Table 3."),
        ("table", [
            ["Size", "Distance S (mm)", "Tolerance (mm)"],
            ["125", "4", "+1"],
            ["160", "5", "+1"],
            ["200", "5", "+1.5"],
            ["250", "6", "+1.5"],
        ]),
    ],
    # 8
    [
        ("h", "4.2 Aligning"),
        ("p", "Correct alignment is essential for a long service life of the buffers and the shaft "
              "bearings. Align the machines at operating temperature where possible, or allow for the "
              "thermal growth given by the machine manufacturer."),
        ("h", "4.2.1 Aligning by means of a straight edge and feeler gauge"),
        ("p", "Place a straight edge across both coupling halves at four points offset by 90 degrees and "
              "measure the radial offset with a feeler gauge. Measure the gap between the faces at the "
              "same four points to determine the angular offset."),
        ("p", "This method is sufficient for sizes up to 160. For larger sizes use a dial gauge."),
    ],
    # 9
    [
        ("h", "Permissible shaft misalignment and tolerances"),
        ("h", "4.2.2 Aligning by means of a dial gauge"),
        ("p", "Fix the dial gauge to one coupling half and turn both shafts together. Read the radial "
              "value on the outer diameter and the axial value on the face of the other half at four "
              "positions."),
        ("p", "Shaft misalignment compensation of the coupling is limited. The permissible values in "
              "Table 4 depend on the nominal size and the speed. Kr is the permissible radial "
              "misalignment and Kw the permissible angular misalignment. Stay within the tolerance to "
              "protect the buffers."),
        ("table", [
            ["Nominal size", "Speed (1/min)", "Kr radial (mm)", "Kw angular (mm)"],
            ["10", "1500", "0,3", "0,7"],
            ["12", "1500", "0,3", "0,8"],
            ["16", "1500", "0,4", "0,9"],
            ["20", "1500", "0,4", "1,0"],
        ]),
        ("p", "Table 4: permissible shaft misalignment. For higher speeds reduce the values in proportion "
              "to the speed."),
    ],
    # 10
    [
        ("h", "5. Commissioning"),
        ("p", "Before the first start check that the set screws are tightened, the guard is fitted and "
              "the alignment values are recorded."),
        ("p", "Turn the drive train by hand if possible. The coupling must turn freely without "
              "contact between the halves."),
        ("p", "Start the drive train and observe the running behaviour. Unusual noise or vibration "
              "indicates misalignment or a damaged buffer; stop the machine and check the coupling."),
        ("p", "After about 24 operating hours check the alignment again at operating temperature and "
              "correct it if necessary."),
    ],
    # 11
    [
        ("h", "6. Maintenance"),
        ("p", "The coupling needs no lubrication. Inspect the buffers for wear at the intervals of the "
              "maintenance schedule of the plant, at the latest every 4000 operating hours."),
        ("p", "Measure the torsional backlash between the halves. If the backlash exceeds the wear limit "
              "in the assembly drawing, replace all buffers of the coupling at the same time."),
        ("p", "Check the alignment during each inspection. A change in alignment often indicates a "
              "foundation or bearing problem; find the cause before correcting the alignment."),
        ("p", "Caution: damage to buffers by oil or solvents shortens their service life. Keep the "
              "coupling clean and dry."),
    ],
    # 12
    [
        ("h", "7. Dismantling"),
        ("p", "Secure the drive train against start-up. Remove the guard and mark the position of the "
              "coupling halves."),
        ("p", "Move the driven machine back until the pins leave the buffer half. Pull the coupling "
              "halves off the shafts with a suitable puller; heat them if necessary."),
        ("p", "Replace worn buffers and damaged pins with original parts before reassembly."),
    ],
    # 13
    [
        ("h", "8. Declaration of the manufacturer"),
        ("p", "The FLEXO-N coupling is intended to be incorporated into a machine. It must not be put "
              "into service until the machine into which it is incorporated has been declared in "
              "conformity with the applicable machinery regulations."),
        ("lines", ["Kupplungsbau Mehlberg GmbH", "Industriestrasse 12", "Mehlberg",
                   "service@kbm-coupling.example"]),
    ],
]
