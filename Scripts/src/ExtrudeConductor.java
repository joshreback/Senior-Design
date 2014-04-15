import java.awt.Desktop;



import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.Arrays;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
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
 * TODO: -Organize control signals: extrude conductor, burn signal, open/close 
 *       clamp and raise/lower arm
 *       -Mount clamp and arm higher so that you don't need to ever lower the buildplate 
 *       -Calculate x & y location of the bins 
 *       -Calculate x, y, z offsets for the clamp and arm 
 *       -Determine times to pause to actuate open/close clamp and raise/lower arm     
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

		// Get pick and place inputs: number of objects and x, y, z locations
		// of each one 
		int numParts = Integer.parseInt(JOptionPane.showInputDialog(
				"How many parts will be placed?"));
		PickAndPlaceObj[] partsToPlace = new PickAndPlaceObj[numParts];
		JTextField xField = new JTextField();
		JTextField yField = new JTextField();
		JTextField zField = new JTextField();
		JPanel myPanel = new JPanel();
		myPanel.setLayout(new BoxLayout(myPanel, BoxLayout.Y_AXIS));
		myPanel.add(new JLabel("x (mm):"));
		myPanel.add(xField);
		myPanel.add(new JLabel("y (mm):"));
		myPanel.add(yField);
		myPanel.add(new JLabel("z (mm):"));
		myPanel.add(zField);
		JLabel msg = new JLabel("Enter the x, y, and z positions for" +
				" part 1");
		myPanel.add(msg, 0);
		for (int i = 0; i < numParts; i++) { 
			xField.setText("");
			yField.setText("");
			zField.setText("");
			myPanel.add(msg, 0);
			JOptionPane.showConfirmDialog(null, myPanel, null, 
					JOptionPane.OK_CANCEL_OPTION);
			double xPos = Double.parseDouble(xField.getText());
			double yPos = Double.parseDouble(yField.getText());
			double zPos = Double.parseDouble(zField.getText());
			partsToPlace[i] = new PickAndPlaceObj(xPos, yPos, zPos);
			myPanel.remove(0);
			msg.setText("Enter the x, y, and z positions for" +
					" part " + (i+2));
			myPanel.add(msg, 0);
		}

		// sort array so that the part that is to be placed closest to the
		// build platform (smallest z) is at the front of the array
		// (partsToPlace < 5)
		Arrays.sort(partsToPlace); 

		// declare variables to use for automating pick and place
		double binX = 1.0; 
		double binY[] = new double[numParts];
		double binZ = 1.0; 
		boolean placedPart[] = new boolean[numParts];
		int partsPlaced = 0;  
		double xOffsetFromClaw = 60.325;
		double yOffsetFromClaw = 53.975;

		// declare variables to use for automating conductor extrusion
		boolean pumpActive = false; 
		boolean subsequentTravelMove = false; 
		double xOffsetFromSyringe = 18.8722;
		double yOffsetFromSyringe = 16.648;
		double feedrate = 300; 
		String tempLine; 
		String line; 
		StringBuilder modifiedFile = new StringBuilder();

		try {
			// Read in input file
			BufferedReader bRead = new BufferedReader(
					new FileReader(inputFile));

			// read file into input
			while ((line = bRead.readLine()) != null) {
				// MakerBot extrudes 0.2 mm layers; layer right above z-level 
				// at which to place is 0.2 higher 
				double zLevel = partsToPlace[partsPlaced].z + 0.2;

				// Skip loop iteration if carriage is too close to edge of workspace
				if (line.contains("X105") || line.contains("X-112")) continue;

				///////////////////////////////////////////////////////////////

				// remove long retract lines 
				if (line.contains("Long Retract Extruder: A")) continue; 
				// Determine whether following lines need to be replaced
				if (line.contains("M135 T1")) { 
					// overwrite line not include toolchange in modified file
					line = "(Used to be a toolchange here)\n";
					pumpActive = true; 
					subsequentTravelMove = false;
				} else if (line.contains("M135 T0")) { 
					// Turn off conductor extrusion when switching to other tool
					line = "M127; (Turns off conductor extrusion)";
					pumpActive = false;
				} else if (pumpActive && line.contains("Travel move") && 
						!subsequentTravelMove) { 
					// append first travel move with gcode for control signal 
					// to extrude conductor and turn motor on 
					line += "\nG4 P500;\nM126;\nG4 P2000;\nM127; (junk signal)" +
							"\nG4 P500;\nM126;\nG4 P160;\nM127;" +
							" (control signal to extrude conductor)" +
							"\nG4 P500;\n M126; (Turn on conductor extrusion)";
					subsequentTravelMove = true; 
				} else if (pumpActive && line.contains("Travel move") && 
						subsequentTravelMove) {
					// turn off conductor extrusion, make travel move, then
					// turn conductor extrusion back on 
					tempLine = line; 
					line = "M127; (turns off conductor extrusion)\n" + 
							tempLine + 
							"\nG4 P500;\nM126;\nG4 P2000;\nM127; (junk signal)" +
							"\nG4 P500;\nM126;\nG4 P160;\n M127; (control signal to extrude conductor)" +
							"\nG4 P500;\n M126; (Turn on conductor extrusion)";
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
								xPos += xOffsetFromSyringe; 
								part = "X" + xPos; 
							}
							if (part.charAt(0) == 'Y') { 
								double yPos = Double.parseDouble(part.substring(1, 
										part.length())); 
								yPos += yOffsetFromSyringe; 
								part = "Y" + yPos; 
							}

							if (part.charAt(0) == 'F') { 
								part = "F" + feedrate + ";"; 
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

				// conditions to pick & place next part:
				// 1. pump is not active (we should have already extruded conductor)
				// 2. line immediately follows extrusion of z-layer at which to
				//    place object
				// 3. Haven't already placed object 
				if (!pumpActive && line.contains("z" + zLevel) && 
						!placedPart[partsPlaced]) { 
					// add in movements & control signals to place part
					double pumpX = xOffsetFromClaw + partsToPlace[partsPlaced].x;
					double pumpY = xOffsetFromClaw + partsToPlace[partsPlaced].y;
					line += ("(START OF PICK AND PLACE CODE)\n" + 
							"G1 X"+ binX + " Y" + binY[partsPlaced] + "F300"  // move to bin
							+ "\nG4 P500;\nM126;\nG4 P2000;\nM127;"  	 // reset MCU
							+ "\nG4 P500;\nM126;\nG4 P880;\nM127;"   	 // control signal to open clamp
							+ "\nG4 P500;\nM126;\nG4 P880;\nM127;"  	 // open clamp
							+ "\nG4 P500;\nM126;\nG4 P2000;\nM127;"  	 // reset MCU
							+ "\nG4 P500;\nM126;\nG4 P400;\nM127;"   	 // control signal to lower arm
							+ "\nG4 P500;\nM126;\nG4 P2000;\nM127;"  	 // lower arm
							+ "\nG4 P500;\nM126;\nG4 P2000;\nM127;"  	 // reset MCU
							+ "\nG4 P500;\nM126;\nG4 P720;\nM127;"   	 // control signal to close clamp
							+ "\nG4 P500;\nM126;\nG4 P2000;\nM127;"      // close clamp
							+ "\nG4 P500;\nM126;\nG4 P2000;\nM127;"      // reset MCU
							+ "\nG4 P500;\nM126;\nG4 P560;\nM127;"       // control signal to raise arm
							+ "\nG4 P500;\nM126;\nG4 P2000;\nM127;"      // raise arm
							+ "\nG4 P500;\nM126;\nG4 P2000;\nM127;"      // lower build plate***
							+ "\nG1 X" + pumpX + " Y" + pumpY + " F300"  // Move carriage to designated x,y coordinate
							+ "\nG4 P500;\nM126;\nG4 P2000;\nM127;"      // reset MCU
							+ "\nG4 P500;\nM126;\nG4 P400;\nM127;"       // control signal to lower arm  
							+ "\nG4 P500;\nM126;\nG4 P400;\nM127;"       // lower arm  
							+ "\nG4 P500;\nM126;\nG4 P2000;\nM127;"      // reset MCU 
							+ "\nG4 P500;\nM126;\nG4 P2000;\nM127;"      // control signal to open clamp 
							+ "\nG4 P500;\nM126;\nG4 P2000;\nM127;"      // open clamp 
							+ "\nG4 P500;\nM126;\nG4 P880;\nM127;"       // reset MCU 
							+ "\nG4 P500;\nM126;\nG4 P560;\nM127;"       // control signal to raise arm 
							+ "\nG4 P500;\nM126;\nG4 P560;\nM127;"       // raise arm 
							+ "(\nEND OF PICK AND PLACE CODE)"); 
					
					// indicate that you have already placed the part 
					placedPart[partsPlaced] = true;
					partsPlaced++; 
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

	private static class PickAndPlaceObj implements Comparable<PickAndPlaceObj> { 
		public double x; 
		public double y; 
		public double z; 

		public PickAndPlaceObj(double xPos, double yPos, double zPos) { 
			this.x = xPos; 
			this.y = yPos; 
			this.z = zPos; 
		}

		@Override
		public int compareTo(PickAndPlaceObj other) { 
			if (this.z > other.z) { 
				return 1; 
			} else if (this.z < other.z) { 
				return -1; 
			} else if (this.x > other.x) { 
					return 1; 
			} else if (other.x > this.x)  {
					return -1;
			} else if (this.y > other.y) { 
					return 1; 
			} else { 
					return -1; 
			}
		}
	}
}
