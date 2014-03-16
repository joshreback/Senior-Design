import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStreamReader;

/**
 * @author joshuareback
 * 
 * This script automates the process of extruding conductive material by
 * parsing a .gcode file corresponding to a Dualstrusion print. 
 * 
 * The print should be designed such that one extruder prints the plastic 
 * geometry while the other extruder prints the conductive  material. 
 * This script parses the gcode to replace toolchanges with the .gcode 
 * commands extrude conductive material. 
 * 
 * TODO Determine whether T0 or T1 corresponds to left extruder.
 * TODO Calculate offset from left/right extruder immediately after toolchange
 */

public class ExtrudeConductor {
	public static void main(String[] args) {
		boolean replaceMode = false; 
		boolean subsequentMove = false; 
		String filename = args[0];
		String tempLine; 
		String line; 
		try {
			// Declare variable to hold input file, each line, and output
			FileInputStream fsIn = new FileInputStream(filename);
            BufferedReader bRead = new BufferedReader(new InputStreamReader(fsIn));
            StringBuilder file = new StringBuilder();

			// read file into input
			while ((line = bRead.readLine()) != null) {
				
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
				} else { 
					line += "\n"; 
				}
				
				file.append(line); 
			} 
			
			//Close the input stream
            bRead.close();
            
			// Load updated file content into new file
            FileWriter fsWrite = new FileWriter(filename.substring(0, 
            		filename.length() - 6)+"_updated.gcode");
            BufferedWriter bWrite = new BufferedWriter(fsWrite);
            bWrite.write(file.toString());
            bWrite.close();
            
		} catch (Exception e) {
			System.out.println("Problem reading file.");
		}

	}
}
