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
 */

public class ExtrudeConductor {
	public static void main(String[] args) {
		JOptionPane.showMessageDialog(null, "Please select the gcode file");

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
		Arrays.sort(partsToPlace); 

		// declare variables to use for automating pick and place
		double binX = -62.5; 
		double binY[] = {-11, 16, 41, 63};  
		boolean placedPart[] = new boolean[numParts];
		int partsPlaced = 0;  
		String[] components; 
		double zLevel = 0.0; 
		double xLeft = 0.0; 
		double xOffsetFromPump = 95.5; 
		double yOffsetFromPump = 58.5;
		double xPickPlace = 0; 
		double yPickPlace = 0; 

		// declare variables to use for automating conductor extrusion
		boolean pumpActive = false; 
		boolean subsequentTravelMove = false; 
		double xOffsetFromSyringe = 18.8722;
		double yOffsetFromSyringe = 16.648;
		double feedrate = 500; 
		String tempLine; 
		String line; 
		StringBuilder modifiedFile = new StringBuilder();
		BufferedReader bRead;

		try {
			// Read in input file
			bRead = new BufferedReader(new FileReader(inputFile));

			// read file into input
			while ((line = bRead.readLine()) != null) {

				// Skip loop iteration if carriage is too close to edge of workspace
				if (line.contains("X105") || line.contains("X-112")) continue;

				///////////////////////////////////////////////////////////////
				components = line.split(" ");
				try { 
					if (components.length > 4 
							&& components[3].charAt(0) == 'Z' 
							&& components[3].length() > 1) { 
						zLevel = Double.parseDouble(components[3].substring(1));
						xLeft = Double.parseDouble(components[1].substring(1));
						if (xLeft < - 40) continue;  // remove left supports

					}
				} catch (Exception e) { 
					System.out.println("error line: " + line);
				}
				// remove pointless support
				if (line.contains("Restart") || (pumpActive && 
					(line.contains("Set speed for tool change")
					|| line.contains("Retract")
					|| line.contains("Support")))) { 
					continue; 
				}
				// Determine whether following lines need to be replaced
				if (line.contains("M135 T1")) { 
					// overwrite line not include toolchange in modified file
					line = "(Pump active)";
					pumpActive = true; 
					subsequentTravelMove = false;
				} else if (line.contains("M135 T0")) { 
					// Turn off conductor extrusion when switching to other tool
					line = "\nG4 P100\nM127; (Turns off conductor extrusion)"
							+"\n(End extra action - extrude)\n(Pump inactive)";
					pumpActive = false; 
				} else if (pumpActive && line.contains("Travel move") && 
						!subsequentTravelMove) { 
					// append first travel move with gcode for control signal 
					// to extrude conductor and turn motor on
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
							tempLine += part + " "; 
						}
						// do not extrude any plastic
						if (tempLine.contains("B")) { 
							tempLine = tempLine.substring(0, tempLine.indexOf("B"));
						}
						// append comment to verify offset 
						tempLine += " (old line: " + line + ")";
					} catch (Exception e) {
						// do nothing 
					}

					line =  "(Begin extra action - extrude)\n" + tempLine + 
							"\nG4 P500;\nM126;\nG4 P2000;\nM127; (junk signal)" +
							"\nG4 P500;\nM126;\nG4 P160;\nM127;" +
							" (control signal to extrude conductor)\nG4 P500;\n" +
							"M126; (Turn on conductor extrusion)\nG4 P50";
					subsequentTravelMove = true; 
				} else if (pumpActive && line.contains("Travel move") && 
						subsequentTravelMove) {
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
							tempLine += part + " "; 
						}
						// do not extrude any plastic
						if (tempLine.contains("B")) { 
							tempLine = tempLine.substring(0, tempLine.indexOf("B"));
						}
						// append comment to verify offset 
						tempLine += " (old line: " + line + ")";
					} catch (Exception e) {
						// do nothing 
					}
					// turn off conductor extrusion, make travel move, then
					// turn conductor extrusion back on 
					line = "\nG4 P10\nM127; (turns off conductor extrusion)\n" +
							"(End extra action - extrude)\n" + 
							tempLine + "\n(Begin extra action - extrude)" + 
							"\nG4 P500;\nM126;\nG4 P2000;\nM127;" +
							" (junk signal)\nG4 P500;\nM126;\nG4 P160;\n" +
							"M127; (control signal to extrude conductor)\nG4" +
							" P500;\nM126; (Turn on conductor extrusion)";
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

				/* 
				 * conditions to pick & place next part:
				 * 1. pump is not active (we should have already extruded conductor)
				 * 2. line immediately follows extrusion of z-layer at which to
				 *    place object
				 * 3. Haven't already placed object 
				 */
				if (!pumpActive && partsPlaced < numParts 
						&& !placedPart[partsPlaced] 
								&& zLevel >= (partsToPlace[partsPlaced].z)) { 
					// add in movements & control signals to place part
					xPickPlace = partsToPlace[partsPlaced].x + xOffsetFromPump;
					yPickPlace = partsToPlace[partsPlaced].y + yOffsetFromPump;
					line += ("(Begin extra action - pick and place)\n"  
							+ "\nG1 X" + binX + " Y" + binY[partsPlaced] 
									+ " F600\n"          			 // move to bin
									+ "G1 Z25 F300" 				 		 // move build plate to "pick level" 
									+ "\nG4 P500;\nM126;\nG4 P2000;\nM127;"  // reset MCU
									+ "\nG4 P500;\nM126;\nG4 P880;\nM127;"   // control signal to open clamp 
									+ "\nG4 P500;\nM126;\nG4 P2500;\nM127;"  // open clamp
									+ "\nG4 P500;\nM126;\nG4 P2000;\nM127;"  // reset MCU
									+ "\nG4 P500;\nM126;\nG4 P400;\nM127;"   // control signal to lower arm
									+ "\nG4 P500;\nM126;\nG4 P28000;\nM127;" // lower arm
									+ "\nG4 P500;\nM126;\nG4 P2000;\nM127;"  // reset MCU
									+ "\nG4 P500;\nM126;\nG4 P720;\nM127;"   // control signal to close clamp
									+ "\nG4 P500;\nM126;\nG4 P2000;\nM127;"  // close clamp
									+ "\nG4 P500;\nM126;\nG4 P2000;\nM127;"  // reset MCU
									+ "\nG4 P500;\nM126;\nG4 P560;\nM127;"   // control signal to raise arm
									+ "\nG4 P500;\nM126;\nG4 P31000;\nM127;" // raise arm
									+ "\nG1 Z" + (zLevel + 5) + " F600" 			 // Move build plate to previous level
									+ "\nG1 X" + xPickPlace + " Y" + yPickPlace 
									+  " F600"  						 // Move carriage to designated x,y coordinate
									+ "\nG4 P500;\nM126;\nG4 P2000;\nM127;"  // reset MCU
									+ "\nG4 P500;\nM126;\nG4 P400;\nM127;"   // control signal to lower arm  
									+ "\nG4 P500;\nM126;\nG4 P12500;\nM127;"  // lower arm  
									+ "\nG4 P500;\nM126;\nG4 P2000;\nM127;"  // reset MCU
									+ "\nG4 P500;\nM126;\nG4 P880;\nM127;"   // control signal to open clamp
									+ "\nG4 P500;\nM126;\nG4 P200;\nM127;"   // open clamp 
									+ "\nG4 P500;\nM126;\nG4 P2000;\nM127;"  // reset MCU
									+ "\nG4 P500;\nM126;\nG4 P560;\nM127;"   // control signal to raise arm 
									+ "\nG4 P500;\nM126;\nG4 P13000;\nM127;" // raise arm 
									+ "\nG4 P500;\nM126;\nG4 P2000;\nM127;"  // reset MCU
									+ "\nG4 P500;\nM126;\nG4 P720;\nM127;"   // control signal to close clamp
									+ "\nG4 P500;\nM126;\nG4 P2000;\nM127;"  // close clamp
									+ "\nG4 P500;\nM126;\nG4 P2000;\nM127;"  // reset MCU
									+ "\nG4 P500;\nM126;\nG4 P400;\nM127;"   // control signal to lower arm  
									+ "\nG4 P500;\nM126;\nG4 P9000;\nM127;"  // lower arm 
									+ "\nG4 P500;\nM126;\nG4 P2000;\nM127;"  // reset MCU
									+ "\nG4 P500;\nM126;\nG4 P880;\nM127;"   // control signal to open clamp
									+ "\nG4 P500;\nM126;\nG4 P200;\nM127;"   // open clamp 
									+ "\nG4 P500;\nM126;\nG4 P2000;\nM127;"  // reset MCU
									+ "\nG4 P500;\nM126;\nG4 P560;\nM127;"   // control signal to raise arm 
									+ "\nG4 P500;\nM126;\nG4 P13000;\nM127;" // raise arm 
									+ "\n(End extra action - pick and place)"); 

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
			String tempFileName = "Temp.gcode"; 
			File tempFile = new File(tempFileName);
			FileWriter tempFileWriter = new FileWriter(tempFile);
			BufferedWriter tempBuffWriter = new BufferedWriter(tempFileWriter);
			tempBuffWriter.write(modifiedFile.toString());
			tempBuffWriter.close();

			// Re-parse modifiedFile to generate finalFile
			bRead = new BufferedReader(new FileReader(tempFile));
			StringBuilder finalFile = new StringBuilder();
			boolean syringeOn = false; 
			boolean appendConductorGCode = false; 
			String conductorGCode = ""; 
			boolean appendPickAndPlace = false; 
			String pickAndPlaceGCode = ""; 
			String extraActions = ""; 
			// read file into input
			while ((line = bRead.readLine()) != null) {
				// Set flags 
				if (line.contains("Pump active")) { 
					syringeOn = true; 
				} else if (line.contains("Begin extra action - extrude")) { 
					appendConductorGCode = true; 
				} else if (line.contains("Begin extra action - pick and place")) { 
					appendPickAndPlace = true; 
				} else if (line.contains("End of print")) {
					System.out.println("END OF PRINT");
					extraActions += pickAndPlaceGCode; 
					finalFile.append(extraActions + "\n");
					appendConductorGCode = false; 
					appendPickAndPlace = false; 
					syringeOn = false; 
				}

				// Add to extraAction string
				if (appendConductorGCode) { 
					conductorGCode += line + "\n";
				} else if (appendPickAndPlace) { 
					pickAndPlaceGCode += line + "\n";
				} else if (syringeOn) { 
					conductorGCode += line + "\n";  
				} else {
					finalFile.append(line + "\n");
				} 
				
				// Clear flags 		
				if (line.contains("End extra action - pick and place")) { 
					appendPickAndPlace = false; 
				} else if (line.contains("End extra action - extrude")) { 
					if (conductorGCode.contains("Spur")) { 
						extraActions += conductorGCode + "\n" + line + "\n";
					}
					conductorGCode = ""; 
					appendConductorGCode = false; 
				} else if (line.contains("Pump inactive")) { 
					syringeOn = false; 
				} 
			}

			// Put modifiedFile into its own file 
			String inputFileName = inputFile.getCanonicalPath(); 
			String newPathAndFileName = inputFileName.substring(0, 
					inputFileName.length() - 6)+"_updated.gcode";
			File outputFile = new File(newPathAndFileName);
			FileWriter fWrite = new FileWriter(outputFile);
			BufferedWriter bWrite = new BufferedWriter(fWrite);
			bWrite.write(finalFile.toString());
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

		public int compareTo(PickAndPlaceObj other) { 
			if (this.z > other.z) { 
				return 1; 
			} else if (this.z < other.z) { 
				return -1; 
			} else { 
				if (this.x > other.x) { 
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
}