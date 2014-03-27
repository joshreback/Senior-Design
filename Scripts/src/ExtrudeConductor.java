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
 * @author joshuareback
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
		boolean replaceMode = false; 
		boolean subsequentMove = false; 
		boolean inMWStartCode = true; 
		String tempLine; 
		String line; 
		File startGCode = new File("/Users/joshuareback/Dropbox/Senior-Design/Scripts/src/startGCode.txt");
		StringBuilder modifiedFile = new StringBuilder();
		
		try {
			// Append repG start GCode in output file
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
				// Skip loop iteration if still in makerware start code
				if (line.equals(";")) inMWStartCode = false; 
				if (inMWStartCode) continue; 
				
				// remove lines which move extruders too close to the edge of
				// the workspace
				/**
				 * TODO: Are these exact codes in every print?
				 * TODO: Add some validation to ensure input gcode never
				 * attempts to print outside the boundaries
				 */
				if (line.contains("X105") || line.contains("X-112")) continue;
				
				// Determine whether following lines need to be replaced
				if (line.contains("M135 T1")) { 
					replaceMode = true; 
					subsequentMove = false; 
				}
				if (line.contains("M135 T0")) { 
					// Turn off conductor extrusion when switching to other tool
					tempLine = line; 
					line = "M127; (Turns off conductor extrusion)\n" + line + "\n";
					replaceMode = false;
				}
				// Determine if you have detected the first travel move
				if (line.contains("Travel move") && replaceMode && 
						!subsequentMove) { 
					subsequentMove = true; 
					// append first travel move with gcode for control signal 
					// to extrude conductor and turn motor on 
					line += ("\nM126;\nG4 P80;\nM127; (control signal to extrude " +
							"conductor)\nG4 P500;\nM126; (Turn on conductor " +
							"extrusion)\n");
				} else if (line.contains("Travel move") && replaceMode && 
						subsequentMove) {
					// turn off conductor extrusion, make travel move, then
					// turn conductor extrusion back on 
					tempLine = line; 
					line = ("M127; (turns off conductor extrusion)\n") + 
							tempLine + ("\nM126;\nG4 P80;\nM127;(control signal" +
									" to extrude conductor)\nG4 P500;\n" +
									"M126; (Turns on conductor extrusion)\n");
				} else if (replaceMode && line.contains("B")) {
					// turn off plastic extrusion during conductor extrusion
					line = line.substring(0, line.indexOf("B")) + "\n";
				} else { 
					line += "\n"; 
				}
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
