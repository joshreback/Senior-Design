import java.awt.Desktop;


import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * @author Joshua Reback
 * 
 * This script automates the process of extruding conductive material by
 * parsing a .gcode file corresponding to a dual extrusion print. 
 * 
 * The print should be designed such that one extruder prints the plastic 
 * geometry while the other extruder prints the conductive material. 
 * This script parses the gcode to replace toolchanges with the .gcode 
 * commands extrude conductive material. 
 * 
 * TODO Calculate offset from left/right extruder immediately after toolchange
 */

public class ExtrudeConductor {
	public static void main(String[] args) {
		JOptionPane.showMessageDialog(null, "Please select the Dual Extrusion File");

		// set up File Chooser 
		File inputFile = null; 
		JFileChooser chooser = new JFileChooser();
		FileNameExtensionFilter filter = new FileNameExtensionFilter(
				"GCode Files", "gcode");
		chooser.setFileFilter(filter);
		int returnVal = chooser.showOpenDialog(chooser);

		// Access input file
		if (returnVal == JFileChooser.APPROVE_OPTION) {
			inputFile = chooser.getSelectedFile(); 
		}

		// declare variables to use in file parsing
		boolean pumpActive = false; 
		boolean subsequentTravelMove = false; 
		boolean inMWStartCode = true; 
		double xOffset = 19.05;
		double yOffset = 14.73; 
		String tempLine; 
		String line; 
		File startGCode = new File("/Users/joshuareback/Dropbox/Senior-Design/Scripts/src/startGCode.txt");
		StringBuilder modifiedFile = new StringBuilder();

		try {
			//Append repG start GCode in output file
			BufferedReader startCode = new BufferedReader(
					new FileReader(startGCode));
			while ((line = startCode.readLine()) != null) {
				modifiedFile.append(line + "\n");
			}

			// Read in input file
			BufferedReader bRead = new BufferedReader(
					new FileReader(inputFile));

			// read file into input
			while ((line = bRead.readLine()) != null) {
				// Skip loop iteration if in MakerWare start code or extruder
				// carriage is too close to edge of workspace
				if (line.equals(";")) inMWStartCode = false;  // never reset
				if (inMWStartCode) continue; 
				if (line.contains("X105") || line.contains("X-112")) continue;
				
				///////////////////////////////////////////////////////////////

				// Determine whether following lines need to be replaced
				if (line.contains("M135 T1")) { 
					pumpActive = true; 
					subsequentTravelMove = false;
				} else if (line.contains("M135 T0")) { 
					// Turn off conductor extrusion when switching to other tool
					tempLine = line; 
					line = "M127; (Turns off conductor extrusion)\n" + tempLine;
					pumpActive = false;
				} else if (pumpActive && line.contains("Travel move") && 
						!subsequentTravelMove) { 
					// append first travel move with gcode for control signal 
					// to extrude conductor and turn motor on 
					line += ("\nM126;\nG4 P80;\nM127; (control signal to extrude " +
							"conductor)\nG4 P500;\nM126; (Turn on conductor " +
							"extrusion)");
					subsequentTravelMove = true; 
				} else if (pumpActive && line.contains("Travel move") && 
						subsequentTravelMove) {
					// turn off conductor extrusion, make travel move, then
					// turn conductor extrusion back on 
					tempLine = line; 
					line = ("M127; (turns off conductor extrusion)\n") + 
							tempLine + ("\nM126;\nG4 P80;\nM127;(control signal" +
									" to extrude conductor)\nG4 P500;\n" +
									"M126; (Turns on conductor extrusion)");
				} else if (pumpActive && line.contains("G1")) {
					// for moves to extrude the conductor: first apply xOffset
					// and yOffset, change feedrate, and don't extrude plastic
					tempLine = ""; 
					try {
						String[] parts = line.split(" ");
						for (String part:parts) { 
							if (part.charAt(0) == 'X') { 
								double xPos = Double.parseDouble(part.substring(1, 
										part.length())); 
								xPos += xOffset; 
								part = "X" + xPos; 
							}
							if (part.charAt(0) == 'Y') { 
								double yPos = Double.parseDouble(part.substring(1, 
										part.length())); 
								yPos += yOffset; 
								part = "Y" + yPos; 
							}
							if (part.charAt(0) == 'F') { 
								double feedrate = Double.parseDouble(part.substring(1, 
										part.length())); 
								feedrate = 100; 
								part = "F" + feedrate; 
							}
							tempLine += part + " "; 
						}
						// do not extrude any plastic
						if (tempLine.contains("B")) { 
							tempLine = tempLine.substring(0, tempLine.indexOf("B"));
						}
						// append comment to verify offset 
						tempLine += " (old line: " + line + ")";
						line = tempLine; 
					} catch (Exception e) {
						// do nothing 
					}
				}
				// no matter what, append a newline character to each gcode line
				line += "\n"; 
				modifiedFile.append(line); 
			} 
			
			
			// Close the input stream
			// Load updated file content into new file in same directory as 
			// input file 
			String inputFileName = inputFile.getCanonicalPath(); 
			String newPathAndFileName = inputFileName.substring(0, 
					inputFileName.length() - 6)+"_updated.gcode";
			File outputFile = new File(newPathAndFileName);
			FileWriter fWrite = new FileWriter(outputFile);
			BufferedWriter bWrite = new BufferedWriter(fWrite);
			bWrite.write(modifiedFile.toString());
			bWrite.close();


			// Notify the user that the task is done and open file
			JOptionPane.showMessageDialog(null, "Click OK to open the new GCode" +
					"\nfile in ReplicatorG", "Continue",
					JOptionPane.DEFAULT_OPTION, null);
			Desktop dt = Desktop.getDesktop();
			dt.open(outputFile);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}
