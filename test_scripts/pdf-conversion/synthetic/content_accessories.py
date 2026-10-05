"""Synthetic accessory documentation that exists only in the compilation bundle.

Folder 2 of the fictional RK&T complete manual: a lube oil purifier, a turning
gear and a steam trap station. None of these topics appears in the operating
manual, the installation requirements or the coupling manual, so the bundle has
content that only it can answer. The pages are deliberately keyword-dense with
varying richness so that the bundle has more signal pages than the converter's
30-chunk signal cap.

All text is invented for Parler document-retrieval fixtures.
"""

LETTERHEAD = "Reinholt, Kessler & Thal AG"
DOC_CODE = "Order 7.318.042 - Accessory documentation"


def _p(heading: str, *paragraphs: str) -> list[tuple]:
    return [("h", heading)] + [("p", p) for p in paragraphs]


PAGES = [
    [
        ("h", "Folder 2 Accessory documentation"),
        ("p", "This folder contains the documentation of the lube oil purifier, the turning gear and the "
              "steam trap station supplied with the turbo-generator set."),
    ],
    _p("Lube oil purifier: overview",
       "The lube oil purifier is a centrifugal separator that removes water and particles from the "
       "turbine lube oil in a side stream. It runs continuously while the oil system is in operation.",
       "Warning: the separator bowl rotates at high speed. Never open the purifier before the bowl has "
       "come to a complete stop."),
    _p("Lube oil purifier: start-up",
       "Before start-up check the gearbox oil level of the purifier and the direction of rotation. "
       "Caution: running the bowl in the wrong direction causes damage to the drive."),
    _p("Lube oil purifier: bowl cleaning",
       "Clean the separator bowl every 500 operating hours, or earlier when the discharge alarm occurs. "
       "Tighten the bowl lock ring to the torque stamped on the ring.",
       "Warning: an incorrectly tightened lock ring can loosen during operation and cause serious "
       "damage. Caution: use only the special tools supplied with the purifier."),
    _p("Lube oil purifier: alarms and trip",
       "The purifier raises an alarm for high vibration, low feed pressure and failed sludge discharge. "
       "High vibration trips the purifier motor.",
       "Fault finding: after a trip, check the bowl for sludge build-up and imbalance before restarting. "
       "Warning: do not restart after a vibration trip until the cause is found."),
    _p("Lube oil purifier: maintenance schedule",
       "Maintenance intervals: bowl cleaning every 500 hours, inspection of the bowl seals every 2000 "
       "hours, overhaul of the drive every 8000 hours. Record all maintenance in the purifier log."),
    _p("Lube oil purifier: lubrication",
       "Lubrication of the purifier gearbox: change the gearbox oil every 2000 hours. Check the oil "
       "level weekly. Caution: overfilling the gearbox causes overheating of the drive."),
    _p("Lube oil purifier: troubleshooting",
       "Trouble: water in the cleaned oil. Cause: wrong gravity disc or feed temperature too low. "
       "Remedy: fit the correct gravity disc and raise the feed temperature to the setpoint.",
       "Trouble: sludge discharge fails. Cause: blocked operating water line. Remedy: clean the "
       "operating water strainer. A repeated discharge fault raises an alarm."),
    _p("Lube oil purifier: setpoints",
       "Feed temperature setpoint, feed pressure alarm limit and vibration trip limit are listed in the "
       "purifier data sheet. Changing a setpoint requires the approval of the plant engineer."),
    _p("Lube oil purifier: spare parts",
       "Keep a set of bowl seals, a gravity disc set and a spare operating water valve in stock."),
    _p("Turning gear: overview",
       "The turning gear rotates the turbine rotor slowly after shutdown so that it cools evenly and "
       "does not bow. It engages automatically at standstill and disengages when the turbine starts."),
    _p("Turning gear: operation",
       "Start the turning gear only when the lube oil pressure is available. Warning: the turning gear "
       "must not be engaged while the rotor is turning above turning speed; damage to the gear teeth "
       "results."),
    _p("Turning gear: alarms and faults",
       "The turning gear raises an alarm when it fails to engage and a fault when the motor current is "
       "too high. A high motor current indicates rubbing in the turbine; stop the turning gear and "
       "investigate. Caution: do not force the rotor with the hand lever."),
    _p("Turning gear: maintenance",
       "Maintenance: inspect the engagement mechanism every 4000 hours and check the gear teeth for "
       "wear. Lubrication of the gear follows the lubrication schedule."),
    _p("Turning gear: commissioning checks",
       "During commissioning check engagement and disengagement at standstill, the motor direction of "
       "rotation and the interlock with the lube oil pressure. Alignment of the turning gear motor is "
       "set at the factory."),
    _p("Turning gear: torque limiter",
       "A torque limiter protects the turning gear against overload. Check the torque setting at each "
       "major inspection; damage to the limiter plates requires replacement."),
    _p("Steam trap station: overview",
       "The steam trap station drains condensate from the live steam line and the casing drains. Each "
       "trap has an isolating valve and a test valve."),
    _p("Steam trap station: testing",
       "Test each steam trap during rounds by opening the test valve. A trap that blows live steam "
       "continuously is faulty and must be replaced."),
    _p("Steam trap station: maintenance",
       "Maintenance: clean the trap strainers every 2000 hours. Caution: isolate and depressurize the "
       "trap before opening it. Warning: hot condensate causes burns."),
    _p("Steam trap station: troubleshooting",
       "Trouble: water hammer in the drain line. Cause: a blocked steam trap. Remedy: clean or replace "
       "the trap; check the alignment of the drain line slope. A malfunction of the drain valves can "
       "cause damage to the turbine blading."),
    _p("Steam trap station: alarms",
       "A high-level alarm in the drain pot indicates a failed trap. After the alarm, check the trap "
       "and the drain valve before restarting the turbine; a trip on drain pot level protects the "
       "blading."),
    _p("Steam trap station: spare parts",
       "Keep a spare trap of each size and a set of strainer screens in stock."),
    _p("Vibration monitoring rack: overview",
       "The vibration monitoring rack measures shaft vibration and bearing housing vibration. It raises "
       "an alarm at the first limit and trips the turbine at the second limit.",
       "Warning: never bridge a trip channel during operation. A fault in a channel is shown on the rack "
       "and raises a fault alarm."),
    _p("Vibration monitoring rack: maintenance and calibration",
       "Maintenance: check the sensor gaps at each major inspection and calibrate the channels. Caution: "
       "a wrong setpoint after calibration causes a false trip or no trip at all; damage to the bearings "
       "can follow."),
    _p("Vibration monitoring rack: commissioning",
       "During commissioning verify every alarm and trip setpoint by injecting a test signal. Check the "
       "alignment of each probe bracket and tighten it to the specified torque."),
    _p("Emergency oil pump battery charger",
       "The battery charger keeps the emergency oil pump battery ready. A charger fault raises an alarm. "
       "Warning: a discharged battery means no bearing lubrication during coast-down after a trip; "
       "damage to the bearings results. Maintenance: test the battery capacity every year."),
    _p("Emergency oil pump battery: troubleshooting",
       "Trouble: the emergency oil pump does not start on test. Cause: battery fault or contactor "
       "malfunction. Remedy: check the battery voltage and the contactor. Danger: battery rooms contain "
       "explosive gas; ventilate before work."),
    _p("Gland steam condenser: operation and alarms",
       "The gland steam condenser keeps the gland leak-off under slight vacuum. A high-level alarm or a "
       "fan fault indicates a malfunction. Caution: without vacuum, steam escapes at the shaft seals and "
       "can cause damage to bearings through water in the oil."),
    _p("Gland steam condenser: maintenance",
       "Maintenance: clean the condenser tubes and check the fan every 4000 hours. Lubrication of the fan "
       "bearings follows the lubrication schedule. Check the fan alignment and belt torque."),
    _p("Overspeed test device: operation",
       "The overspeed test device lets the operator test the mechanical overspeed trip below rated speed. "
       "Warning: the turbine must be at no load. A failed test is a fault that must be eliminated before "
       "commissioning or restarting."),
    _p("Overspeed test device: setpoints and damage",
       "The trip setpoint of the test device is set at the factory. Caution: changing the setpoint "
       "without approval is not permitted. Damage to the test piston causes a malfunction of the trip; "
       "replace the piston during maintenance."),
    _p("Accessory maintenance summary",
       "Maintenance, lubrication and alarm test intervals of all accessories are summarised in the "
       "maintenance schedule. Warning: skipping an alarm or trip test can hide a fault until damage "
       "occurs. Torque values and setpoints are listed in the accessory data sheets."),
]
