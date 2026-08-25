# SGS connector icons

Design source for the QC45 connector selection page.

The live 640x480 UI renders the same geometry directly with Java2D in `MainMenuPanel.java`, so no SVG renderer is required on the QC45 Java 7 runtime.

## Connector geometry

- **CCS2**: based on front views of real European CCS Combo 2 DC cable plugs. The DC cable plug shown in the UI uses the populated PE + CP/PP contacts in the Type-2 half and the two large DC contacts below.
- **CHAdeMO**: circular connector face with two large DC power contacts and surrounding control/communication contacts.
- **Type 2 / IEC 62196-2**: characteristic seven-contact AC connector layout.

## Visual language

- black/anthracite connector bodies
- SGS yellow `#FFD600` as the active outline/keying accent
- light grey contact rings
- transparent background for the design-source SVGs

The SVG files are design/reference assets. The operational display uses the matching Java2D drawing code to keep the connector symbols crisp and dependency-free on the original 640x480 HMI.
