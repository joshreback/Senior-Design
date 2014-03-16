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
		String filename = args[0];
		String line; 
		try {
			// Declare variable to hold input file, each line, and output
			FileInputStream fsIn = new FileInputStream(filename);
            BufferedReader bRead = new BufferedReader(new InputStreamReader(fsIn));
            StringBuilder file = new StringBuilder();

			// read file into input
			while ((line = bRead.readLine()) != null) {
				// Determine whether following lines need to be replaced
				// NOTE: assuming conductor is extruded from tool T1
				if (line.contains("M108 T1")) replaceMode = true;  
				if (line.contains("M108 T0")) replaceMode = false;

				if (replaceMode && line.contains("M101")) { 
					// replace with gcode for control signal to extrude 
					// conductor and turn motor on 
					line = ("M126;\nG4 P80;\nM127;\nG4 P500;\nM126; " + 
							"(Turns on conductor extrusion)\n");
					file.append(line); 
				} else if (replaceMode && line.contains("M103")) { 
					// replace with gcode to turn motor off
					line = "M127; (Turns off conductor extrusion)\n";
					file.append(line); 
				} else { 
					file.append(line + "\n");
				}
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
